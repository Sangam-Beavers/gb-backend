package com.gb.wallet.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;

/**
 * {@link RateLimitHelper#tryAcquire(String, long, Duration)} 단위 테스트(redis-util-2).
 *
 * <p>고정 윈도 카운터(INCR+첫증가 PEXPIRE atomic Lua)가 돌려준 count로 한도 이하/초과를 가르는 분기와,
 * Redis 장애 시 fail-open(통과)을 검증한다. 카운트 주입은 {@code getScript().eval}을 스텁해 시뮬레이션한다.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitHelperTest {

    @Mock private RedissonClient redissonClient;
    @Mock private RScript rScript;
    @InjectMocks private RateLimitHelper helper;

    private static final String KEY = "ratelimit:transfer:user-1";
    private static final long LIMIT = 30L;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    @SuppressWarnings("unchecked")
    private void stubCount(long count) {
        given(redissonClient.getScript(StringCodec.INSTANCE)).willReturn(rScript);
        given(rScript.<Long>eval(any(), anyString(), any(), eq(List.<Object>of(KEY)), any()))
                .willReturn(count);
    }

    @Test
    @DisplayName("윈도 내 카운트가 한도 이하(count<=limit)면 허용(true)")
    void 한도이하_허용() {
        stubCount(LIMIT); // 30번째 = 한도와 같음 → 허용
        assertThat(helper.tryAcquire(KEY, LIMIT, WINDOW)).isTrue();
    }

    @Test
    @DisplayName("윈도 내 카운트가 한도 초과(count>limit)면 거부(false)")
    void 한도초과_거부() {
        stubCount(LIMIT + 1); // 31번째 → 거부
        assertThat(helper.tryAcquire(KEY, LIMIT, WINDOW)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 시 fail-open(통과) — rate-limit은 가용성 우선 보조장치(분산락의 fail-closed와 다름)")
    @SuppressWarnings("unchecked")
    void redis장애_failOpen() {
        given(redissonClient.getScript(StringCodec.INSTANCE)).willReturn(rScript);
        given(rScript.<Long>eval(any(), anyString(), any(), any(List.class), any()))
                .willThrow(new RedisException("redis down"));

        assertThat(helper.tryAcquire(KEY, LIMIT, WINDOW)).isTrue();
    }
}
