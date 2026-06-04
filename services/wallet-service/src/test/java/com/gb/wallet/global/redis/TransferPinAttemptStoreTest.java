package com.gb.wallet.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * {@link TransferPinAttemptStore#recordFailure} 단위 테스트(WSCH-06). 단기(10분)·24h 누적 두 카운터의
 * 잠금 분기를 검증한다 — 특히 24h 누적 한도 도달 시 단기가 아니라 <b>장기(24h) 잠금</b>으로 에스컬레이션해
 * "무제한 5회/10분" 재시도를 일일 캡으로 막는지가 핵심.
 */
@ExtendWith(MockitoExtension.class)
class TransferPinAttemptStoreTest {

    @Mock private RedissonClient redissonClient;
    @InjectMocks private TransferPinAttemptStore store;

    private static final String USER = "user-public-id-1";
    private static final String DAY_KEY = "pin:fail24h:" + USER;
    private static final String SHORT_KEY = "pin:fail:" + USER;
    private static final String LOCK_KEY = "pin:lock:" + USER;

    @Test
    @DisplayName("WSCH-06: 24h 누적이 한도(15)에 도달하면 장기 24h 잠금으로 에스컬레이션 — 단기 카운터는 증가시키지 않는다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordFailure_24h누적_장기잠금() {
        RAtomicLong dayFails = org.mockito.Mockito.mock(RAtomicLong.class);
        RAtomicLong shortFails = org.mockito.Mockito.mock(RAtomicLong.class);
        RBucket lockBucket = org.mockito.Mockito.mock(RBucket.class);
        given(redissonClient.getAtomicLong(DAY_KEY)).willReturn(dayFails);
        given(redissonClient.getAtomicLong(SHORT_KEY)).willReturn(shortFails);
        given(redissonClient.getBucket(LOCK_KEY)).willReturn(lockBucket);
        given(dayFails.incrementAndGet()).willReturn(15L); // 24h 누적 한도 도달

        boolean locked = store.recordFailure(USER);

        assertThat(locked).isTrue();
        verify(lockBucket).set("locked", 24L, TimeUnit.HOURS); // 단기(10분)가 아니라 장기(24h)
        verify(shortFails).delete();
        verify(shortFails, never()).incrementAndGet(); // 누적 캡 경로는 단기 카운터를 건드리지 않는다
    }

    @Test
    @DisplayName("단기 윈도 5회 도달(누적은 한도 미만)이면 10분 단기 잠금")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordFailure_단기5회_10분잠금() {
        RAtomicLong dayFails = org.mockito.Mockito.mock(RAtomicLong.class);
        RAtomicLong shortFails = org.mockito.Mockito.mock(RAtomicLong.class);
        RBucket lockBucket = org.mockito.Mockito.mock(RBucket.class);
        given(redissonClient.getAtomicLong(DAY_KEY)).willReturn(dayFails);
        given(redissonClient.getAtomicLong(SHORT_KEY)).willReturn(shortFails);
        given(redissonClient.getBucket(LOCK_KEY)).willReturn(lockBucket);
        given(dayFails.incrementAndGet()).willReturn(5L);   // 누적 5 < 15
        given(shortFails.incrementAndGet()).willReturn(5L); // 단기 5 = 한도

        boolean locked = store.recordFailure(USER);

        assertThat(locked).isTrue();
        verify(lockBucket).set("locked", 10L, TimeUnit.MINUTES); // 단기 10분
        verify(shortFails).delete();
    }

    @Test
    @DisplayName("한도 미만 실패면 잠금 없이 false")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordFailure_한도미만_false() {
        RAtomicLong dayFails = org.mockito.Mockito.mock(RAtomicLong.class);
        RAtomicLong shortFails = org.mockito.Mockito.mock(RAtomicLong.class);
        given(redissonClient.getAtomicLong(DAY_KEY)).willReturn(dayFails);
        given(redissonClient.getAtomicLong(SHORT_KEY)).willReturn(shortFails);
        given(dayFails.incrementAndGet()).willReturn(3L);
        given(shortFails.incrementAndGet()).willReturn(3L);

        boolean locked = store.recordFailure(USER);

        assertThat(locked).isFalse();
        verify(redissonClient, never()).getBucket(LOCK_KEY); // 잠금 미설정
    }
}
