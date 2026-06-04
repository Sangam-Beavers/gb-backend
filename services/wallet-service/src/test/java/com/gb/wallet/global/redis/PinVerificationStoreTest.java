package com.gb.wallet.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * {@link PinVerificationStore} 단위 테스트(TX-PIN). pin-verify 성공 마커의 발급(TTL)·원자 소비(GETDEL
 * 단일사용)·무효화·fail-closed를 검증한다. Redisson은 mock으로 시뮬레이션한다(TransferPinAttemptStoreTest 패턴).
 */
@ExtendWith(MockitoExtension.class)
class PinVerificationStoreTest {

    @Mock private RedissonClient redissonClient;
    @InjectMocks private PinVerificationStore store;

    private static final String USER = "user-public-id-1";
    private static final String KEY = "pin:verified:" + USER;

    @Test
    @DisplayName("markVerified: pin:verified 키에 180초 TTL 마커(\"verified\")를 남긴다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void markVerified_TTL마커() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(KEY)).willReturn(bucket);

        store.markVerified(USER);

        verify(bucket).set("verified", 180L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("consumeVerified: 마커가 있으면 true (원자 소비 GETDEL)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void consumeVerified_있으면_true() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(KEY)).willReturn(bucket);
        given(bucket.getAndDelete()).willReturn("verified");

        assertThat(store.consumeVerified(USER)).isTrue();
        verify(bucket).getAndDelete(); // get+delete를 한 연산으로 — best-effort 삭제 아님
    }

    @Test
    @DisplayName("consumeVerified: 마커가 없으면(이미 소비/만료/미존재) false")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void consumeVerified_없으면_false() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(KEY)).willReturn(bucket);
        given(bucket.getAndDelete()).willReturn(null);

        assertThat(store.consumeVerified(USER)).isFalse();
    }

    @Test
    @DisplayName("단일사용: GETDEL이라 첫 호출만 true, 같은 마커를 두 번 인가하지 못한다(double-spend 방지)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void consumeVerified_단일사용() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(KEY)).willReturn(bucket);
        // GETDEL 시맨틱: 첫 호출은 값 반환, 두 번째는 이미 삭제돼 null.
        given(bucket.getAndDelete()).willReturn("verified", (Object) null);

        assertThat(store.consumeVerified(USER)).as("1회 검증 = 1회 인가").isTrue();
        assertThat(store.consumeVerified(USER)).as("같은 마커 재사용 불가").isFalse();
    }

    @Test
    @DisplayName("fail-closed: consumeVerified 중 Redis 장애면 COMMON5000 전파(송금 차단 — rate-limit fail-open과 대비)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void consumeVerified_Redis장애_failClosed() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(KEY)).willReturn(bucket);
        given(bucket.getAndDelete()).willThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> store.consumeVerified(USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("fail-closed: markVerified 중 Redis 장애면 COMMON5000 전파(검증을 실패 처리 — 증표 없는 통과 방지)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void markVerified_Redis장애_failClosed() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(KEY)).willReturn(bucket);
        willThrow(new RuntimeException("redis down")).given(bucket).set(any(), anyLong(), any());

        assertThatThrownBy(() -> store.markVerified(USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("clearVerified: 마커 키를 삭제한다(PIN 재설정 시 stale 무효화)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void clearVerified_삭제() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(KEY)).willReturn(bucket);

        store.clearVerified(USER);

        verify(bucket).delete();
    }

    @Test
    @DisplayName("clearVerified: Redis 장애여도 예외를 삼킨다(best-effort — TTL로 자동 만료)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void clearVerified_장애_무시() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(KEY)).willReturn(bucket);
        willThrow(new RuntimeException("redis down")).given(bucket).delete();

        assertThatCode(() -> store.clearVerified(USER)).doesNotThrowAnyException();
    }
}
