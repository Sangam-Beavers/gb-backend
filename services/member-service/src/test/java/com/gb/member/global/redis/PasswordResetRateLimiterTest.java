package com.gb.member.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link PasswordResetRateLimiter} 단위 테스트(MEM-04). Lua가 돌려준 카운트로 허용/거부를 가르는 분기와
 * Redis 장애 시 fail-open을 검증한다. 실제 Redis/Lua 실행은 Testcontainers 백로그(여기선 카운트 결과만 mock).
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @InjectMocks private PasswordResetRateLimiter limiter;

    @SuppressWarnings("unchecked")
    private void stubCount(Long count) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any())).willReturn(count);
    }

    @Test
    @DisplayName("한도 이하(카운트<=5)면 허용(true)")
    void 한도내_허용() {
        stubCount(5L); // 윈도 내 5번째 = 한도(5)와 같음 → 허용
        assertThat(limiter.tryAcquire("a@example.com")).isTrue();
    }

    @Test
    @DisplayName("한도 초과(카운트>5)면 거부(false)")
    void 한도초과_거부() {
        stubCount(6L);
        assertThat(limiter.tryAcquire("a@example.com")).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 시 fail-open(통과) — 정상 사용자의 재설정을 막지 않는다")
    @SuppressWarnings("unchecked")
    void redis장애_failOpen() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .willThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

        assertThat(limiter.tryAcquire("a@example.com")).isTrue();
    }

    @Test
    @DisplayName("MEM2 회귀: rate-limit 키는 trim+소문자 정규화 — 대소문자·앞뒤 공백 변형이 같은 버킷")
    @SuppressWarnings("unchecked")
    void 키_정규화_대소문자공백_같은버킷() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any())).willReturn(1L);

        // 같은 이메일의 케이스·공백 변형 3종. 정규화 전이면 각각 다른 Redis 키 → 한도 우회(메일 폭탄).
        limiter.tryAcquire("User@X.com ");
        limiter.tryAcquire("user@x.com");
        limiter.tryAcquire("  USER@X.COM");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(3))
                .execute(any(RedisScript.class), keysCaptor.capture(), any());
        assertThat(keysCaptor.getAllValues())
                .as("대소문자·앞뒤 공백 변형이 모두 같은 정규화 키로 모인다")
                .allSatisfy(keys -> assertThat(keys)
                        .containsExactly("ratelimit:pwreset:user@x.com"));
    }
}
