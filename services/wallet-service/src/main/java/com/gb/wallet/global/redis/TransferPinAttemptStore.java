package com.gb.wallet.global.redis;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 송금 PIN 연속 실패 횟수와 잠금 상태를 Redis로 관리한다.
 *
 * <p>PIN 해시는 영구 데이터라 DB(wallets)에 두지만, 실패 횟수/잠금은 "일정 시간 뒤 자동 해제"되는 임시
 * 상태라 Redis TTL이 적합하다(잔액=DB, 임시=Redis 패턴 — 비번재설정 토큰과 동일 사상).
 *
 * <ul>
 *   <li>{@code pin:fail:{userPublicId}} — 연속 실패 횟수(원자적 증가). 첫 실패 시 윈도우 TTL을 건다.</li>
 *   <li>{@code pin:lock:{userPublicId}} — 잠금 플래그. {@value #MAX_ATTEMPTS}회 실패 시 생성되며
 *       {@value #LOCK_MINUTES}분 뒤 자동 해제된다.</li>
 * </ul>
 */
@Component
public class TransferPinAttemptStore {

    private static final String FAIL_KEY_PREFIX = "pin:fail:";
    private static final String LOCK_KEY_PREFIX = "pin:lock:";

    /** 연속 실패 허용 횟수(이 횟수째 실패면 잠금). */
    private static final int MAX_ATTEMPTS = 5;
    /** 잠금 지속 시간(분). */
    private static final long LOCK_MINUTES = 10L;
    /** 실패 카운트 유지 윈도우(분) — 이 시간 내 연속 실패만 누적. */
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(10L);

    /** Redisson eager connect 회피 — QuoteRedisRepository와 동일 패턴(@Autowired @Lazy). */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    /** 현재 잠겨 있는지. */
    public boolean isLocked(String userPublicId) {
        return redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId).isExists();
    }

    /**
     * 실패 1회를 기록한다. 누적이 {@value #MAX_ATTEMPTS}회 이상이면 잠금을 설정하고 카운트를 비운다.
     *
     * @return 이번 실패로 "잠금 상태가 됐으면" true (호출 측이 PIN_LOCKED로 응답하도록)
     */
    public boolean recordFailure(String userPublicId) {
        RAtomicLong fails = redissonClient.getAtomicLong(FAIL_KEY_PREFIX + userPublicId);
        long count = fails.incrementAndGet();
        if (count == 1L) {
            // 첫 실패에만 윈도우 TTL을 건다(연속 실패만 누적, 오래된 실패는 자동 만료).
            fails.expire(FAIL_WINDOW);
        }
        if (count >= MAX_ATTEMPTS) {
            redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId)
                    .set("locked", LOCK_MINUTES, TimeUnit.MINUTES);
            fails.delete();
            return true;
        }
        return false;
    }

    /** 검증 성공 시 실패 카운트/잠금을 초기화한다. */
    public void reset(String userPublicId) {
        redissonClient.getAtomicLong(FAIL_KEY_PREFIX + userPublicId).delete();
        redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId).delete();
    }
}
