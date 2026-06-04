package com.gb.wallet.domain.exchange.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.exchange.dto.QuoteData;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

/**
 * 환전 견적(QuoteData)을 Redis에 TTL 5분으로 저장/조회한다.
 *
 * <p>견적은 "5분간 유효한 약속"이라 영구 저장(DB)이 아니라 Redis가 적합하다. TTL이 지나면 Redis가
 * 키를 자동 삭제하므로, 실행 시 조회해서 없으면 "견적 만료"(EXCHANGE4002)로 처리한다 —
 * 만료 시각을 별도로 계산·비교할 필요가 없다.
 *
 * <p>QuoteData(record)는 JSON 문자열로 직렬화해 RBucket에 담는다. 직렬화는 ObjectMapper로 처리하며,
 * 직렬화 실패는 견적을 진행할 수 없는 상황이라 {@code BusinessException}(COMMON5000)으로 던진다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class QuoteRedisRepository {

    private static final String KEY_PREFIX = "quote:";
    private static final long TTL_MINUTES = 5L;

    /** Redisson eager connect 회피 — IdempotencyCacheHelper와 동일 패턴(@Autowired @Lazy). */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    private final ObjectMapper objectMapper;

    /** 견적을 5분 TTL로 저장한다. */
    public void save(QuoteData quote) {
        try {
            String json = objectMapper.writeValueAsString(quote);
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + quote.quotePublicId());
            bucket.set(json, TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("견적 Redis 저장 실패. quotePublicId={}", quote.quotePublicId(), e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }

    /** 견적 조회. 없거나(만료/미존재) 역직렬화 실패면 {@link Optional#empty()}. */
    public Optional<QuoteData> find(String quotePublicId) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + quotePublicId);
        String json = bucket.get();
        if (json == null) {
            return Optional.empty(); // 만료(TTL 경과) 또는 미존재
        }
        try {
            return Optional.of(objectMapper.readValue(json, QuoteData.class));
        } catch (Exception e) {
            log.warn("견적 Redis 역직렬화 실패. quotePublicId={}", quotePublicId, e);
            return Optional.empty();
        }
    }

    /**
     * 견적을 <b>원자적으로 소비</b>한다(get + delete를 한 연산으로 — Redis GETDEL). 값을 반환한
     * <b>단 한 호출</b>만 견적을 획득하고, 이미 소비/만료/미존재면 {@link Optional#empty()}를 반환한다.
     *
     * <p><b>왜 원자 소비인가(WEXB-01):</b> 견적을 "조회 후 실행, 성공 시 별도 삭제"(best-effort {@link #delete})로
     * 다루면, 같은 견적을 <b>서로 다른 idempotency_key</b>로 동시 요청할 때 두 요청이 모두 견적을 보고 각자
     * 실행해 <b>이중 환전</b>이 난다(transactions의 idempotency_key UNIQUE는 키가 달라 못 막음). 소비를 GETDEL로
     * 원자화하면 경쟁에서 이긴 1건만 견적을 얻고 나머지는 empty가 되어 실행을 막는다.
     *
     * <p>{@link #find}와 달리 best-effort가 아니다 — 소비 성공/실패가 실행 진행의 게이트이므로, Redis 장애나
     * 역직렬화 실패는 "소비 불확실" 상태이며 그대로 진행하면 이중 환전 위험이 있어 예외를 전파한다(COMMON5000).
     */
    public Optional<QuoteData> getAndDelete(String quotePublicId) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + quotePublicId);
        String json;
        try {
            json = bucket.getAndDelete();
        } catch (Exception e) {
            log.error("견적 원자 소비(getAndDelete) 실패 — 이중 환전 방지 위해 중단. quotePublicId={}", quotePublicId, e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
        if (json == null) {
            return Optional.empty(); // 이미 소비됨(경쟁에서 진 쪽)·만료·미존재
        }
        try {
            return Optional.of(objectMapper.readValue(json, QuoteData.class));
        } catch (Exception e) {
            // 소비(삭제)는 됐지만 데이터 손상 — 진행 불가. 견적은 이미 사라졌으므로 재시도하려면 재견적 필요.
            log.error("견적 원자 소비 후 역직렬화 실패. quotePublicId={}", quotePublicId, e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * 실행 완료된 견적을 삭제한다(재사용 방지). 없으면 no-op.
     *
     * <p>이미 환전이 완료된 뒤의 정리 작업이라 best-effort다 — Redis 일시 장애로 삭제가 실패해도 견적은
     * TTL(5분)로 자동 만료되므로, 예외를 전파해 완료된 거래 응답을 깨뜨리지 않고 경고만 남긴다(find의 비치명 패턴).
     *
     * <p>※ 환전 실행 경로는 {@link #getAndDelete}(원자 소비)로 전환됐다(WEXB-01). 이 메서드는 향후 다른
     * 정리 용도를 위해 유지한다.
     */
    public void delete(String quotePublicId) {
        try {
            redissonClient.getBucket(KEY_PREFIX + quotePublicId).delete();
        } catch (Exception e) {
            log.warn("견적 Redis 삭제 실패(무시 — TTL로 자동 만료). quotePublicId={}", quotePublicId, e);
        }
    }
}
