package com.gb.wallet.global.redis;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Redisson {@link RAtomicLong} 기반 고정 윈도(fixed-window) rate limiter 헬퍼.
 *
 * <p>키별로 카운터를 1씩 올리고, 첫 증가(=1) 시점에 윈도 TTL을 걸어 윈도가 지나면 카운터가 자동 만료된다.
 * 윈도 내 호출 수가 {@code limit} 이하면 허용(true), 초과하면 거부(false)다. 임계값/윈도는 호출자가
 * 정책 설정에서 받아 넘긴다(헬퍼는 메커니즘만, 정책 수치는 서비스 — CLAUDE.md §2).
 *
 * <p><b>fail-open:</b> Redis 장애로 카운터 접근이 실패하면 막지 않고 통과(true)시킨다. rate-limit은 보안
 * 보조 장치이고, 카운트를 못 센다고 본업을 막지 않는다(가용성 우선). 분산 락의 fail-closed(503)와 성격이
 * 다르다 — 락은 "조용한 중복"을 막는 정합성 장치라 못 잡으면 거부하지만, rate-limit은 폭주 완화용이다.
 */
@Slf4j
@Component
public class RateLimitHelper {

    /**
     * {@code @Autowired @Lazy}: 이유는 {@link DistributedLockHelper#redissonClient} 주석 참고.
     * (Redisson eager connect 회피 + Lombok {@code @RequiredArgsConstructor}가 {@code @Lazy}를
     * 생성자 파라미터로 못 옮기는 한계 우회)
     */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    /**
     * {@code key}의 고정 윈도 카운터를 1 증가시키고, 윈도 내 호출이 {@code limit} 이하인지 반환한다.
     * 첫 증가(=1) 시점에만 {@code window} TTL을 건다(이후 증가는 TTL을 갱신하지 않아 윈도가 미끄러지지 않는다).
     *
     * @param key    전체 카운터 키(네임스페이스 포함, 예: {@code ratelimit:account-verify:{userPublicId}})
     * @param limit  윈도 내 허용 호출 수
     * @param window 카운터 만료 윈도
     * @return 허용되면 {@code true}, 초과하면 {@code false}. Redis 장애 시 fail-open으로 {@code true}.
     */
    public boolean tryAcquire(String key, long limit, Duration window) {
        try {
            // INCR + (첫 증가=1일 때만)PEXPIRE를 한 Lua로 원자 실행 — 둘 사이에 JVM/연결이 끊겨도
            // TTL 없는 영구 키가 남지 않게 한다(원자성만 추가, 카운트·TTL·fail-open 동작은 동일). ARGV는 ms.
            long count = redissonClient.getScript(StringCodec.INSTANCE).<Long>eval(
                    RScript.Mode.READ_WRITE,
                    INCR_WITH_TTL_LUA,
                    RScript.ReturnType.INTEGER,
                    List.<Object>of(key),
                    String.valueOf(window.toMillis()));
            return count <= limit;
        } catch (RuntimeException e) {
            log.warn("Rate-limit 카운터 접근 실패 — fail-open(통과). key={}", key, e);
            return true;
        }
    }

    /** 고정 윈도 카운터의 INCR + 첫 증가(=1) 시 PEXPIRE를 원자 실행하는 Lua. KEYS[1]=카운터 키, ARGV[1]=윈도(ms). */
    private static final String INCR_WITH_TTL_LUA =
            "local count = redis.call('incr', KEYS[1]); "
            + "if count == 1 then redis.call('pexpire', KEYS[1], ARGV[1]); end; "
            + "return count;";
}