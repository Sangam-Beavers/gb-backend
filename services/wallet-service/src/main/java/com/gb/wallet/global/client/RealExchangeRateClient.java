package com.gb.wallet.global.client;

import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영용 환율 클라이언트. Redis {@code rate:KRW-<통화>} 키에서 환율 값을 읽는다.
 *
 * <p>{@link MockExchangeRateClient} 와 동일한 인터페이스({@link ExchangeRateClient})를 구현해
 * 호출 측(Service) 코드는 Profile 만 바꿔도 그대로 동작한다(BankClient 패턴과 동일).
 *
 * <p>데이터 소스: gb-mcp-servers/exchange-updater 가 매일 자정 외부 환율 API
 * (open.er-api.com) 를 호출해 같은 키 패턴으로 저장한다. mcp-exchange (챗봇 환율 도구) 와도 SSOT
 * 일치 — gb-backend/docs/database.md §7.
 *
 * <p>⚠️ 환율 방향 변환: Redis 에 저장된 값은 "1 KRW → X 외화" (예: {@code rate:KRW-VND = 17.5}
 * 는 1 KRW = 17.5 VND). 본 인터페이스 계약은 "1 외화 → KRW" 환율을 돌려주는 것({@code MockExchangeRateClient}
 * 의 값과 동일 방향)이므로, Redis 값의 <b>역수</b>를 반환한다 — 1 / 17.5 ≈ 0.057 (1 VND ≈ 0.057 KRW).
 *
 * <p>Profile 분리(빈 충돌 방지를 위해 명시적으로 나눈다):
 * <ul>
 *   <li>{@code dev, test}: {@link MockExchangeRateClient} (고정 환율표, Redis 미의존)
 *   <li>{@code stage, prod}: 본 클래스 (Redis 실시간 값)
 * </ul>
 *
 * <p>키 없음(TTL 만료 / 미지원 통화) 이나 파싱 실패 시 {@code null} 반환 — 호출 측(Service)이
 * {@code TRANSFER4002} (미지원 통화) 또는 상위 예외로 매핑한다(MockExchangeRateClient 와 동일 정책).
 */
@Slf4j
@Profile({"stage", "prod"})
@Component
public class RealExchangeRateClient implements ExchangeRateClient {

    private static final String KEY_PREFIX = "rate:KRW-";

    /** 직전(전일) 값 키 접미사. exchange-updater 가 매일 자정 새 값을 박기 전 직전 값을 이 키로 백업한다. */
    private static final String PREV_SUFFIX = ":prev";

    /** "1 외화 → KRW" 변환 후 BigDecimal 의 scale (소수점 자리). 충분히 넉넉하게 8자리. */
    private static final int DIVIDE_SCALE = 8;

    /**
     * Redisson eager connect 회피 — QuoteRedisRepository 와 동일 패턴(@Autowired @Lazy).
     * 테스트 환경(Redis 미가동)에서 컨텍스트 로딩이 깨지는 걸 막는다.
     */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    @Override
    public BigDecimal getRateToKrw(CurrencyType currency) {
        // KRW 자기 환율은 1로 고정 (Redis 조회 불필요).
        if (currency == CurrencyType.KRW) {
            return BigDecimal.ONE;
        }
        return readKrwRate(KEY_PREFIX + currency.name());
    }

    /**
     * 직전(전일) "1 {@code currency} → KRW" 환율. {@code rate:KRW-<통화>:prev} 키를 읽어 역수로 변환한다.
     * 첫 실행 직후(prev 미생성)나 TTL 만료로 키가 없으면 {@code null} — 호출 측에서 등락률 0 처리.
     */
    @Override
    public BigDecimal getPrevRateToKrw(CurrencyType currency) {
        if (currency == CurrencyType.KRW) {
            return BigDecimal.ONE;
        }
        return readKrwRate(KEY_PREFIX + currency.name() + PREV_SUFFIX);
    }

    /**
     * Redis 값("1 KRW → X 외화")을 읽어 인터페이스 계약인 "1 외화 → KRW"(역수)로 변환한다.
     * 키 없음/0 이하/파싱 실패 시 {@code null} 반환 — 호출 측에서 의미에 맞게 처리(현재 환율은 TRANSFER4002,
     * 직전 환율은 등락률 0).
     */
    private BigDecimal readKrwRate(String key) {
        // exchange-updater(Python) / mcp-exchange 는 값을 순수 UTF-8 문자열("18.5")로 쓴다.
        // Redisson 의 기본 코덱은 Kryo5Codec 이라 그냥 getBucket(key) 로 읽으면 평문 문자열을
        // Kryo 객체로 역직렬화하려다 KryoException("unregistered class ID")으로 깨진다(→ 500).
        // 전역 코덱을 바꾸면 송금 멱등성/락 등 다른 Redisson 사용처가 영향받으므로, 환율 버킷에만
        // per-bucket StringCodec 을 지정해 평문 문자열 그대로 읽는다(writer 측 포맷과 일치).
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        String value = bucket.get();

        if (value == null) {
            // 현재 환율: 미지원 통화 또는 cron 미실행(TTL 만료). 직전 환율: 첫 실행 직후엔 정상적으로 없음.
            log.warn("환율 키 없음 (cron 미실행 / TTL 만료 / 미지원 통화): key={}", key);
            return null;
        }

        try {
            // Redis 값: "1 KRW → X 외화" (외화/KRW)
            BigDecimal foreignPerKrw = new BigDecimal(value);
            if (foreignPerKrw.signum() <= 0) {
                log.error("환율 값이 0 또는 음수: key={} value={}", key, value);
                return null;
            }
            // 반환: "1 외화 → Y KRW" (KRW/외화) = 1 / (외화/KRW)
            return BigDecimal.ONE.divide(foreignPerKrw, DIVIDE_SCALE, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            log.error("환율 값 파싱 실패: key={} value={}", key, value, e);
            return null;
        }
    }
}
