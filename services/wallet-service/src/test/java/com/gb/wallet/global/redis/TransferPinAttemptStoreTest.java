package com.gb.wallet.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * {@link TransferPinAttemptStore} 단위 테스트(WSCH-06 + 후속). 단기(10분)·24h 누적 두 카운터의 잠금 분기와
 * 검증 성공 시 reset 동작을 검증한다 — 특히 24h 누적 한도 도달 시 단기가 아니라 <b>장기(24h) 잠금</b>으로
 * 에스컬레이션해 "무제한 5회/10분" 재시도를 일일 캡으로 막는지가 핵심.
 *
 * <p>카운터 증가는 INCR+첫증가 PEXPIRE를 묶은 atomic Lua(getScript().eval)로 수행하므로, 카운트 주입은
 * {@code rScript.eval}을 키별로 스텁해 시뮬레이션한다(WSCH-06 후속 — 비원자 incr/expire의 영구 키 잔존 차단).
 */
@ExtendWith(MockitoExtension.class)
class TransferPinAttemptStoreTest {

    @Mock private RedissonClient redissonClient;
    @Mock private RScript rScript;
    @InjectMocks private TransferPinAttemptStore store;

    private static final String USER = "user-public-id-1";
    private static final String DAY_KEY = "pin:fail24h:" + USER;
    private static final String SHORT_KEY = "pin:fail:" + USER;
    private static final String LOCK_KEY = "pin:lock:" + USER;

    /** atomic Lua(getScript().eval)가 해당 키에 대해 주어진 카운트를 반환하도록 스텁한다. */
    @SuppressWarnings("unchecked")
    private void stubIncr(String key, long count) {
        given(rScript.<Long>eval(any(), anyString(), any(), eq(List.<Object>of(key)), any()))
                .willReturn(count);
    }

    @Test
    @DisplayName("WSCH-06: 24h 누적이 한도(15)에 도달하면 장기 24h 잠금으로 에스컬레이션 — 단기 카운터는 증가시키지 않는다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordFailure_24h누적_장기잠금() {
        RBucket lockBucket = mock(RBucket.class);
        RAtomicLong shortFails = mock(RAtomicLong.class);
        given(redissonClient.getScript(StringCodec.INSTANCE)).willReturn(rScript);
        stubIncr(DAY_KEY, 15L); // 24h 누적 한도 도달(atomic Lua가 15 반환)
        given(redissonClient.getBucket(LOCK_KEY)).willReturn(lockBucket);
        given(redissonClient.getAtomicLong(SHORT_KEY)).willReturn(shortFails);

        boolean locked = store.recordFailure(USER);

        assertThat(locked).isTrue();
        verify(lockBucket).set("locked", 24L, TimeUnit.HOURS); // 단기(10분)가 아니라 장기(24h)
        verify(shortFails).delete();
        // 누적 캡 경로는 단기 카운터를 건드리지 않는다(단기 키 atomic incr 미호출).
        verify(rScript, never()).<Long>eval(any(), anyString(), any(), eq(List.<Object>of(SHORT_KEY)), any());
    }

    @Test
    @DisplayName("단기 윈도 5회 도달(누적은 한도 미만)이면 10분 단기 잠금")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordFailure_단기5회_10분잠금() {
        RBucket lockBucket = mock(RBucket.class);
        RAtomicLong shortFails = mock(RAtomicLong.class);
        given(redissonClient.getScript(StringCodec.INSTANCE)).willReturn(rScript);
        stubIncr(DAY_KEY, 5L);   // 누적 5 < 15
        stubIncr(SHORT_KEY, 5L); // 단기 5 = 한도
        given(redissonClient.getBucket(LOCK_KEY)).willReturn(lockBucket);
        given(redissonClient.getAtomicLong(SHORT_KEY)).willReturn(shortFails);

        boolean locked = store.recordFailure(USER);

        assertThat(locked).isTrue();
        verify(lockBucket).set("locked", 10L, TimeUnit.MINUTES); // 단기 10분
        verify(shortFails).delete();
    }

    @Test
    @DisplayName("한도 미만 실패면 잠금 없이 false")
    void recordFailure_한도미만_false() {
        given(redissonClient.getScript(StringCodec.INSTANCE)).willReturn(rScript);
        stubIncr(DAY_KEY, 3L);
        stubIncr(SHORT_KEY, 3L);

        boolean locked = store.recordFailure(USER);

        assertThat(locked).isFalse();
        verify(redissonClient, never()).getBucket(LOCK_KEY); // 잠금 미설정
    }

    @Test
    @DisplayName("Defect1: reset()은 단기·24h 누적·잠금 3개 키를 모두 삭제한다(성공=정당 소유자 증명, 현행 설계 유지)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reset_세개키_모두삭제() {
        RAtomicLong shortFails = mock(RAtomicLong.class);
        RAtomicLong dayFails = mock(RAtomicLong.class);
        RBucket lockBucket = mock(RBucket.class);
        given(redissonClient.getAtomicLong(SHORT_KEY)).willReturn(shortFails);
        given(redissonClient.getAtomicLong(DAY_KEY)).willReturn(dayFails);
        given(redissonClient.getBucket(LOCK_KEY)).willReturn(lockBucket);

        store.reset(USER);

        verify(shortFails).delete();
        verify(dayFails).delete();   // 24h 누적도 리셋 — brute-force는 success를 못 만들어 우회 불가(설계 의도)
        verify(lockBucket).delete();
    }
}
