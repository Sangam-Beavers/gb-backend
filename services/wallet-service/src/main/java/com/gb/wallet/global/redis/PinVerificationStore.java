package com.gb.wallet.global.redis;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 송금 PIN 검증 성공 증표(단명 마커)를 Redis로 관리한다(TX-PIN).
 *
 * <p><b>왜 필요한가:</b> {@code /pin-verify} 성공은 {@link TransferPinAttemptStore#reset}으로 실패/잠금
 * 카운터만 비울 뿐, "이 사용자가 방금 PIN을 통과했다"는 서버측 증표를 남기지 않았다. 그래서 클라이언트가
 * {@code /pin-verify}를 건너뛰고 {@code POST /transfers}(또는 정기송금 설정)를 직접 호출하면 PIN 2차인증을
 * 우회해 자금이 이동할 수 있었다. 이 store가 검증 성공 시 단명 마커를 남기고, 송금/정기설정 게이트
 * ({@code TransferPinGate})가 이를 <b>원자 소비</b>해야 진행하도록 강제한다.
 *
 * <p><b>단일사용(GETDEL):</b> 마커 소비는 {@link RBucket#getAndDelete()}(Redis GETDEL)로 원자화한다 —
 * 1회 검증이 정확히 1회 송금만 인가한다(환전 견적 {@code QuoteRedisRepository}의 double-spend 방지와 동일
 * 사상, WEXB-01). 마커를 "조회 후 best-effort 삭제"로 다루면 동시 요청이 같은 마커로 두 번 송금할 수 있다.
 *
 * <p><b>fail-closed:</b> rate-limit({@code RateLimitHelper})은 Redis 장애 시 fail-open(통과)이지만, 이 게이트는
 * 금융 2차인증이라 <b>fail-closed</b>다 — 마커 확인이 불확실하면 송금을 진행하지 않고 예외(COMMON5000)를
 * 전파한다. {@link #markVerified} 저장 실패도 마찬가지로 전파해, 증표가 안 남았는데 검증 성공으로 오인되는
 * 것을 막는다(사용자는 재검증).
 *
 * <ul>
 *   <li>{@code pin:verified:{userPublicId}} — verify 성공 시 {@value #TTL_SECONDS}초 단명 마커.
 *       {@code TransferPinAttemptStore}의 {@code pin:fail:}/{@code pin:lock:}/{@code pin:fail24h:} 네임스페이스 확장.</li>
 * </ul>
 */
@Slf4j
@Component
public class PinVerificationStore {

    private static final String KEY_PREFIX = "pin:verified:";
    /** 마커 값 — 존재 여부만 의미 있어 sentinel 문자열을 쓴다({@code TransferPinAttemptStore}의 "locked"와 동일 방식). */
    private static final String MARKER_VALUE = "verified";
    /** 마커 유효 시간(초) — verify→확인→execute UI 홉에 충분하면서, 누수돼도 상시 권한이 되지 않을 만큼 짧게. */
    private static final long TTL_SECONDS = 180L;

    /** Redisson eager connect 회피 — TransferPinAttemptStore/QuoteRedisRepository와 동일 패턴(@Autowired @Lazy). */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    /**
     * PIN 검증 성공 마커를 {@value #TTL_SECONDS}초 TTL로 남긴다. 오직 {@code verifyPin} 해시 일치 직후에만 호출돼야 한다.
     *
     * <p>저장 실패는 fail-closed로 전파한다 — 증표가 없는데 검증 성공으로 오인되면 사용자가 게이트에서 막히는
     * 것보다, 검증 자체를 실패로 보고 재검증을 유도하는 편이 안전하다(QuoteRedisRepository.save와 동일).
     */
    public void markVerified(String userPublicId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + userPublicId);
            bucket.set(MARKER_VALUE, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("PIN 검증 마커 저장 실패 — fail-closed(검증 실패 처리). userPublicId={}", userPublicId, e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * 마커를 <b>원자적으로 소비</b>한다(GETDEL). 마커가 있었으면 {@code true}(= 직전 PIN 검증을 1회 한정 인가),
     * 이미 소비/만료/미존재면 {@code false}.
     *
     * <p>Redis 장애로 소비 성공/실패가 불확실하면 그대로 진행 시 PIN 우회 위험이 있어 예외를 전파한다(fail-closed,
     * COMMON5000). 호출 측({@code TransferPinGate})은 {@code false}를 "미검증"으로 보고 송금을 차단한다.
     */
    public boolean consumeVerified(String userPublicId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + userPublicId);
            return bucket.getAndDelete() != null;
        } catch (Exception e) {
            log.error("PIN 검증 마커 소비(getAndDelete) 실패 — fail-closed로 송금 중단. userPublicId={}", userPublicId, e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * 잔존 마커를 무효화한다(PIN을 새로 설정/변경할 때). best-effort — 삭제 실패해도 마커는 TTL로 곧 만료되므로
     * 경고만 남긴다.
     *
     * <p><b>의도(방어적 불변식):</b> 새로 설정/변경된 PIN은 직전 검증을 절대 물려받지 않는다. 현재 {@code setPin}은
     * 최초 설정만 허용해(이미 있으면 충돌) 잔존 마커가 생길 경로가 없지만, 향후 변경(change-PIN) 도입 시
     * stale 마커가 새 PIN 송금을 인가하지 못하도록 미리 막는다.
     */
    public void clearVerified(String userPublicId) {
        try {
            redissonClient.getBucket(KEY_PREFIX + userPublicId).delete();
        } catch (Exception e) {
            log.warn("PIN 검증 마커 삭제 실패(무시 — TTL로 자동 만료). userPublicId={}", userPublicId, e);
        }
    }
}
