package com.gb.wallet.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
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

    // --- tryLockWithWatchdog (WSCH-03, redis-util-2) ---

    private static final String BATCH_KEY = "scheduler:scheduled-transfer";

    @Test
    @DisplayName("watchdog: leaseTime 미지정 tryLock(2-arg)으로 획득 — 고정 lease 오버로드를 쓰지 않아 watchdog 자동 갱신 활성")
    void watchdog_획득_leaseTime미지정() throws Exception {
        given(redissonClient.getLock(BATCH_KEY)).willReturn(lock);
        given(lock.tryLock(3L, TimeUnit.SECONDS)).willReturn(true); // leaseTime 없는 오버로드 = watchdog 활성

        assertThat(helper.tryLockWithWatchdog(BATCH_KEY)).isSameAs(lock);
        // 고정 lease(3-arg) 오버로드는 호출하면 안 된다 — 호출하면 watchdog이 꺼져 배치가 5초 넘기면 락이 풀린다.
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("watchdog: 대기시간 내 미획득(false)이면 null")
    void watchdog_미획득_null() throws Exception {
        given(redissonClient.getLock(BATCH_KEY)).willReturn(lock);
        given(lock.tryLock(3L, TimeUnit.SECONDS)).willReturn(false);

        assertThat(helper.tryLockWithWatchdog(BATCH_KEY)).isNull();
    }

    @Test
    @DisplayName("watchdog: Redis 장애(RedisException)도 null로 정규화(호출 측 처리)")
    void watchdog_Redis예외_null() throws Exception {
        given(redissonClient.getLock(BATCH_KEY)).willReturn(lock);
        given(lock.tryLock(3L, TimeUnit.SECONDS)).willThrow(new RedisException("redis down"));

        assertThat(helper.tryLockWithWatchdog(BATCH_KEY)).isNull();
    }

    // --- tryLockTwoWallets (wallet-lock-1) ---

    @Test
    @DisplayName("두-wallet: Redis 장애(RedisException)도 null로 정규화 — 500이 아니라 호출 측 503(COMMON5031)으로 매핑되게")
    void multiLock_Redis예외_null() throws Exception {
        RLock lock1 = Mockito.mock(RLock.class);
        RLock lock2 = Mockito.mock(RLock.class);
        RLock multiLock = Mockito.mock(RLock.class);
        given(redissonClient.getLock("lock:wallet:1")).willReturn(lock1);
        given(redissonClient.getLock("lock:wallet:2")).willReturn(lock2);
        given(redissonClient.getMultiLock(lock1, lock2)).willReturn(multiLock);
        given(multiLock.tryLock(3L, 5L, TimeUnit.SECONDS)).willThrow(new RedisException("redis down"));

        // 이전엔 tryLockTwoWallets만 RuntimeException catch가 없어 RedisException이 새어 generic 500이 됐다.
        assertThat(helper.tryLockTwoWallets(1L, 2L)).isNull();
    }

    @Test
    @DisplayName("두-wallet: 인자 역순이어도 wallet_id 오름차순 키로 MultiLock 획득")
    void multiLock_획득_오름차순키() throws Exception {
        RLock lock1 = Mockito.mock(RLock.class);
        RLock lock2 = Mockito.mock(RLock.class);
        RLock multiLock = Mockito.mock(RLock.class);
        given(redissonClient.getLock("lock:wallet:1")).willReturn(lock1); // lower=1
        given(redissonClient.getLock("lock:wallet:2")).willReturn(lock2); // higher=2
        given(redissonClient.getMultiLock(lock1, lock2)).willReturn(multiLock);
        given(multiLock.tryLock(3L, 5L, TimeUnit.SECONDS)).willReturn(true);

        assertThat(helper.tryLockTwoWallets(2L, 1L)).isSameAs(multiLock); // 역순 인자 → 동일 오름차순 키
    }
}
