package com.gb.wallet.domain.transaction.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.TransferPinAttemptStore;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * TransferPinServiceImpl 단위 테스트.
 *
 * <p>송금 PIN: 해시는 wallets에 저장(여기선 Wallet 엔티티로 검증), 실패/잠금은 Redis(TransferPinAttemptStore mock).
 * 평문 PIN은 저장하지 않고 {@link PasswordEncoder}로 대조한다.
 */
@ExtendWith(MockitoExtension.class)
class TransferPinServiceImplTest {

    private static final String USER = "user-1";
    private static final String PIN = "123456";
    private static final String STORED_HASH = "STORED_HASH";

    @Mock private WalletRepository walletRepository;
    @Mock private TransferPinAttemptStore attemptStore;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private TransferPinServiceImpl service;

    private Wallet wallet(boolean withPin) {
        Wallet w = Wallet.builder()
                .publicId("wallet-pub")
                .userPublicId(USER)
                .status(WalletStatus.ACTIVE)
                .build();
        if (withPin) {
            w.changeTransferPin(STORED_HASH);
        }
        return w;
    }

    // ───────────────────────── PIN 설정 ─────────────────────────

    @Test
    @DisplayName("setPin: 미설정 지갑이면 BCrypt 해시를 저장한다(평문 미저장)")
    void setPin_성공() {
        Wallet w = wallet(false);
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(w));
        when(passwordEncoder.encode(PIN)).thenReturn("ENCODED");

        service.setPin(USER, PIN);

        assertThat(w.getTransferPinHash()).isEqualTo("ENCODED");
        assertThat(w.hasTransferPin()).isTrue();
    }

    @Test
    @DisplayName("setPin: 이미 PIN이 있으면 COMMON4091로 거절하고 인코딩하지 않는다")
    void setPin_이미설정_COMMON4091() {
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(wallet(true)));

        assertThatThrownBy(() -> service.setPin(USER, PIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_ALREADY_EXISTS);

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("schedule-pin-5(WTX-05): 동결(SUSPENDED) 지갑이면 WALLET4003, PIN 인코딩/저장하지 않는다")
    void setPin_동결지갑_WALLET4003() {
        Wallet suspended = Wallet.builder()
                .publicId("wallet-pub").userPublicId(USER).status(WalletStatus.SUSPENDED).build();
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.setPin(USER, PIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_INACTIVE);

        verify(passwordEncoder, never()).encode(anyString()); // 비활성 지갑은 PIN을 인코딩조차 안 함
        assertThat(suspended.hasTransferPin()).isFalse();
    }

    @Test
    @DisplayName("setPin: 지갑이 없으면 WALLET4001")
    void setPin_지갑없음_WALLET4001() {
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPin(USER, PIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);
    }

    // ───────────────────────── PIN 검증 ─────────────────────────

    @Test
    @DisplayName("verifyPin: 일치하면 통과하고 실패 카운트를 리셋한다")
    void verifyPin_성공_리셋() {
        when(attemptStore.isLocked(USER)).thenReturn(false);
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(wallet(true)));
        when(passwordEncoder.matches(PIN, STORED_HASH)).thenReturn(true);

        service.verifyPin(USER, PIN);

        verify(attemptStore).reset(USER);
        verify(attemptStore, never()).recordFailure(anyString());
    }

    @Test
    @DisplayName("verifyPin: 불일치면 실패 기록 후 TRANSFER4007 (아직 잠김 아님)")
    void verifyPin_불일치_TRANSFER4007() {
        when(attemptStore.isLocked(USER)).thenReturn(false);
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(wallet(true)));
        when(passwordEncoder.matches(PIN, STORED_HASH)).thenReturn(false);
        when(attemptStore.recordFailure(USER)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyPin(USER, PIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TransferErrorCode.PIN_MISMATCH);

        verify(attemptStore).recordFailure(USER);
        verify(attemptStore, never()).reset(anyString());
    }

    @Test
    @DisplayName("verifyPin: 이번 불일치로 한도 초과되면 TRANSFER4008(잠김)")
    void verifyPin_불일치로_잠김_TRANSFER4008() {
        when(attemptStore.isLocked(USER)).thenReturn(false);
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(wallet(true)));
        when(passwordEncoder.matches(PIN, STORED_HASH)).thenReturn(false);
        when(attemptStore.recordFailure(USER)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyPin(USER, PIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TransferErrorCode.PIN_LOCKED);
    }

    @Test
    @DisplayName("verifyPin: 이미 잠겨 있으면 TRANSFER4008 + 지갑조회/대조를 하지 않는다")
    void verifyPin_이미잠김_TRANSFER4008() {
        when(attemptStore.isLocked(USER)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyPin(USER, PIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TransferErrorCode.PIN_LOCKED);

        verify(walletRepository, never()).findByUserPublicId(anyString());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("verifyPin: PIN 미설정이면 TRANSFER4009, 대조하지 않음")
    void verifyPin_미설정_TRANSFER4009() {
        when(attemptStore.isLocked(USER)).thenReturn(false);
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(wallet(false)));

        assertThatThrownBy(() -> service.verifyPin(USER, PIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TransferErrorCode.PIN_NOT_SET);

        verifyNoInteractions(passwordEncoder);
        verify(attemptStore, never()).recordFailure(anyString());
    }
}
