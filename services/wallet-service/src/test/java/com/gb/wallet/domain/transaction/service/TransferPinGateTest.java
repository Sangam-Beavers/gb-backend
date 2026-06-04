package com.gb.wallet.domain.transaction.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.PinVerificationStore;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TransferPinGate} 단위 테스트(TX-PIN). pin-verify 마커의 원자 소비 인가와, 마커가 없을 때의
 * 사유 구분(미설정 TRANSFER4009 / 미검증 TRANSFER4010)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TransferPinGateTest {

    @Mock private PinVerificationStore pinVerificationStore;
    @Mock private WalletRepository walletRepository;
    @InjectMocks private TransferPinGate gate;

    private static final String USER = "user-1";

    private Wallet wallet(boolean withPin) {
        Wallet w = Wallet.builder()
                .publicId("wallet-pub").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        if (withPin) {
            w.changeTransferPin("STORED_HASH");
        }
        return w;
    }

    @Test
    @DisplayName("마커 존재: 통과하고 추가 DB 조회를 하지 않는다(consume-first 정상 경로)")
    void requireVerified_마커있음_통과_지갑조회없음() {
        given(pinVerificationStore.consumeVerified(USER)).willReturn(true);

        assertThatCode(() -> gate.requireVerified(USER)).doesNotThrowAnyException();

        verifyNoInteractions(walletRepository); // 정상 경로는 지갑을 조회하지 않는다
    }

    @Test
    @DisplayName("마커 없음 + PIN 설정됨: TRANSFER4010(검증 필요)")
    void requireVerified_마커없음_PIN있음_TRANSFER4010() {
        given(pinVerificationStore.consumeVerified(USER)).willReturn(false);
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet(true)));

        assertThatThrownBy(() -> gate.requireVerified(USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TransferErrorCode.PIN_VERIFICATION_REQUIRED);
    }

    @Test
    @DisplayName("마커 없음 + PIN 미설정: TRANSFER4009(설정 먼저)")
    void requireVerified_마커없음_PIN미설정_TRANSFER4009() {
        given(pinVerificationStore.consumeVerified(USER)).willReturn(false);
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet(false)));

        assertThatThrownBy(() -> gate.requireVerified(USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TransferErrorCode.PIN_NOT_SET);
    }

    @Test
    @DisplayName("마커 없음 + 지갑 자체가 없음: WALLET4001")
    void requireVerified_마커없음_지갑없음_WALLET4001() {
        given(pinVerificationStore.consumeVerified(USER)).willReturn(false);
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> gate.requireVerified(USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);
    }
}
