package com.gb.wallet.global.redis;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
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
        //     INCR + 첫 증가(=1) PEXPIRE를 atomic Lua로 묶어 — incr와 expire 사이에 JVM/연결이 끊겨도
        //     TTL 없는 영구 키(=24h 캡이 영영 안 빠지는 영구 잠금)가 남지 않게 한다(WSCH-06 회귀 차단).
        long dayCount = incrementAndGetWithTtl(DAY_FAIL_KEY_PREFIX + userPublicId, DAY_WINDOW);
        // (2) 누적 한도 도달 → 장기 잠금으로 에스컬레이션(단기 잠금보다 우선). 일일 시도 총량을 캡한다.
        if (dayCount >= DAY_MAX_ATTEMPTS) {
            redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId)
                    .set("locked", LONG_LOCK_HOURS, TimeUnit.HOURS);
            redissonClient.getAtomicLong(FAIL_KEY_PREFIX + userPublicId).delete();
            return true;
        }

        // (3) 단기(10분) 윈도 카운터 — 24h 카운터와 동일하게 atomic Lua(INCR + 첫 증가 PEXPIRE)로 센다.
        long count = incrementAndGetWithTtl(FAIL_KEY_PREFIX + userPublicId, FAIL_WINDOW);
        if (count >= MAX_ATTEMPTS) {
            redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId)
                    .set("locked", LOCK_MINUTES, TimeUnit.MINUTES);
            redissonClient.getAtomicLong(FAIL_KEY_PREFIX + userPublicId).delete();
            return true;
        }
        return false;
    }

    /**
     * {@code key} 고정 윈도 카운터를 1 증가시키고, 첫 증가(=1)일 때만 {@code window} TTL을 건다 — 두 연산을
     * 한 Lua로 원자 실행한다({@code RateLimitHelper}와 동일 패턴). incr와 expire를 분리하면 그 사이 프로세스가
     * 죽을 때 TTL 없는 영구 키가 남아 카운터가 영영 만료되지 않는다(영구 캡/잠금) — 이를 막는다.
     *
     * @return 증가 후 현재 카운트
     */
    private long incrementAndGetWithTtl(String key, Duration window) {
        return redissonClient.getScript(StringCodec.INSTANCE).<Long>eval(
                RScript.Mode.READ_WRITE,
                INCR_WITH_TTL_LUA,
                RScript.ReturnType.INTEGER,
                List.<Object>of(key),
                String.valueOf(window.toMillis()));
    }

    /** 고정 윈도 카운터의 INCR + 첫 증가(=1) 시 PEXPIRE를 원자 실행하는 Lua(RateLimitHelper와 동일). KEYS[1]=키, ARGV[1]=윈도(ms). */
    private static final String INCR_WITH_TTL_LUA =
            "local count = redis.call('incr', KEYS[1]); "
            + "if count == 1 then redis.call('pexpire', KEYS[1], ARGV[1]); end; "
            + "return count;";

    /**
     * 검증 성공 시 실패 카운트(단기·24h 누적)/잠금을 모두 초기화한다.
     *
     * <p><b>24h 누적까지 함께 리셋하는 이유(설계 의도, WSCH-06):</b> PIN 검증 성공은 정당한 소유자임을
     * 증명하므로 일일 누적 실패 캡(24h)도 비운다. 24h 캡(15회)은 "연속 실패(brute-force)"를 조이기 위한
     * 장치이고, 성공이 끼어들면 brute-force가 아니다 — 공격자는 PIN을 모르면 success를 만들 수 없어 이 reset을
     * 유발할 수 없으므로 캡 우회가 아니다. 오히려 PIN을 자주 쓰는 정상 사용자가 가끔 오타를 내도 성공 때마다
     * 누적이 비워져 24h 장기 잠금에 잘못 걸리지 않는다(가용성). (round6 감사가 "일일 하드캡"으로 바꾸자고 제안한
     * 부분이나, 위 분석상 현행이 더 합리적이라 유지한다.)
     */
    public void reset(String userPublicId) {
        redissonClient.getAtomicLong(FAIL_KEY_PREFIX + userPublicId).delete();
        redissonClient.getAtomicLong(DAY_FAIL_KEY_PREFIX + userPublicId).delete();
        redissonClient.getBucket(LOCK_KEY_PREFIX + userPublicId).delete();
    }
}
