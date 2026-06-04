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
    /** 24h 누적 실패 카운터 — 단기 잠금 사이클이 리셋돼도 살아남아 일일 시도 총량을 캡한다(WSCH-06). */
    private static final String DAY_FAIL_KEY_PREFIX = "pin:fail24h:";

    /** 연속 실패 허용 횟수(이 횟수째 실패면 단기 잠금). */
    private static final int MAX_ATTEMPTS = 5;
    /** 단기 잠금 지속 시간(분). */
    private static final long LOCK_MINUTES = 10L;
    /** 실패 카운트 유지 윈도우(분) — 이 시간 내 연속 실패만 누적. */
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(10L);

    /** 24h 누적 실패 한도(이 횟수째 실패면 장기 잠금으로 에스컬레이션). 단기 5회 × 3사이클 = 15. */
    private static final int DAY_MAX_ATTEMPTS = 15;
    /** 24h 누적 카운터 유지 윈도우. */
    private static final Duration DAY_WINDOW = Duration.ofHours(24L);
    /** 누적 한도 도달 시 장기 잠금 시간(시간). */
    private static final long LONG_LOCK_HOURS = 24L;

    /** Redisson eager connect 회피 — QuoteRedisRepository와 동일 패턴(@Autowired @Lazy). */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    /** 현재 잠겨 있는지. */
    public boolean isLocked(String userPublicId) {
        return redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId).isExists();
    }

    /**
     * 실패 1회를 기록한다. 단기(10분) 윈도 {@value #MAX_ATTEMPTS}회면 10분 잠금, 24h 누적 {@value #DAY_MAX_ATTEMPTS}회면
     * {@value #LONG_LOCK_HOURS}h 장기 잠금으로 에스컬레이션한다(WSCH-06 — 무제한 5회/10분 재시도를 일일 캡으로 제한).
     *
     * @return 이번 실패로 "잠금 상태가 됐으면" true (호출 측이 PIN_LOCKED로 응답하도록)
     */
    public boolean recordFailure(String userPublicId) {
        // (1) 24h 누적 카운터 — 단기 잠금이 카운트를 리셋해도 이 카운터는 24h 동안 유지된다.
        RAtomicLong dayFails = redissonClient.getAtomicLong(DAY_FAIL_KEY_PREFIX + userPublicId);
        long dayCount = dayFails.incrementAndGet();
        if (dayCount == 1L) {
            dayFails.expire(DAY_WINDOW);
        }
        // (2) 누적 한도 도달 → 장기 잠금으로 에스컬레이션(단기 잠금보다 우선). 일일 시도 총량을 캡한다.
        if (dayCount >= DAY_MAX_ATTEMPTS) {
            redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId)
                    .set("locked", LONG_LOCK_HOURS, TimeUnit.HOURS);
            redissonClient.getAtomicLong(FAIL_KEY_PREFIX + userPublicId).delete();
            return true;
        }

        // (3) 단기(10분) 윈도 카운터 — 기존 동작 유지.
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

    /** 검증 성공 시 실패 카운트(단기·24h 누적)/잠금을 모두 초기화한다. */
    public void reset(String userPublicId) {
        redissonClient.getAtomicLong(FAIL_KEY_PREFIX + userPublicId).delete();
        redissonClient.getAtomicLong(DAY_FAIL_KEY_PREFIX + userPublicId).delete();
        redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId).delete();
    }
}
