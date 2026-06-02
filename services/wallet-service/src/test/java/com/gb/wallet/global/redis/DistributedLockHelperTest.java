package com.gb.wallet.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

/**
 * {@link DistributedLockHelper#tryLock(String)} 단위 테스트. 모든 획득 실패가 동일하게 {@code null}로
 * 정규화되는지(호출자가 COMMON5031로 매핑) 검증한다 — 특히 Redis 장애(RuntimeException)가 500으로 새지
 * 않아야 한다.
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockHelperTest {

    @Mock private RedissonClient redissonClient;
    @Mock private RLock lock;
    @InjectMocks private DistributedLockHelper helper;

    private static final String KEY = "lock:account-register:user-1";

    @AfterEach
    void clearInterrupt() {
        // 테스트 간 interrupt 상태 누수 방지(interrupted()는 flag를 읽고 clear).
        Thread.interrupted();
    }

    @Test
    @DisplayName("획득 성공이면 RLock을 반환한다")
    void 획득성공() throws Exception {
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3L, 5L, TimeUnit.SECONDS)).willReturn(true);

        assertThat(helper.tryLock(KEY)).isSameAs(lock);
    }

    @Test
    @DisplayName("대기시간 내 미획득(false)이면 null")
    void 미획득_null() throws Exception {
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3L, 5L, TimeUnit.SECONDS)).willReturn(false);

        assertThat(helper.tryLock(KEY)).isNull();
    }

    @Test
    @DisplayName("Redis 장애(RedisException)도 null로 정규화 — 500이 아니라 호출 측 503으로 매핑되게")
    void Redis예외_null() throws Exception {
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3L, 5L, TimeUnit.SECONDS)).willThrow(new RedisException("redis down"));

        assertThat(helper.tryLock(KEY)).isNull();
    }

    @Test
    @DisplayName("InterruptedException이면 null + interrupt flag 복원")
    void interrupted_null_그리고_flag복원() throws Exception {
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3L, 5L, TimeUnit.SECONDS)).willThrow(new InterruptedException());

        assertThat(helper.tryLock(KEY)).isNull();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
