package com.gb.wallet.domain.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.service.impl.WalletBalanceWriterImpl;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.WalletStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link WalletBalanceWriterImpl} 단위 테스트. "행 보장(get-or-create)"의 세 경로를 검증한다 —
 * 이미 있으면 no-op / 없으면 0원 행 생성 / 동시 생성(UNIQUE 위반)은 흡수(예외 비전파).
 */
@ExtendWith(MockitoExtension.class)
class WalletBalanceWriterTest {

    @Mock private WalletBalanceRepository walletBalanceRepository;
    @InjectMocks private WalletBalanceWriterImpl writer;

    private final Wallet wallet = Wallet.builder()
            .publicId("wallet-pid").userPublicId("user-1").status(WalletStatus.ACTIVE).build();

    @Test
    @DisplayName("이미 행이 있으면 no-op — INSERT하지 않는다")
    void 이미있으면_noop() {
        given(walletBalanceRepository.existsByWalletAndCurrencyCode(wallet, CurrencyType.KRW))
                .willReturn(true);

        writer.ensureBalanceRow(wallet, CurrencyType.KRW);

        verify(walletBalanceRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("행이 없으면 0원 행을 생성한다")
    void 없으면_0원행_생성() {
        given(walletBalanceRepository.existsByWalletAndCurrencyCode(wallet, CurrencyType.KRW))
                .willReturn(false);

        writer.ensureBalanceRow(wallet, CurrencyType.KRW);

        ArgumentCaptor<WalletBalance> captor = ArgumentCaptor.forClass(WalletBalance.class);
        verify(walletBalanceRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCurrencyCode()).isEqualTo(CurrencyType.KRW);
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("동시 생성으로 UNIQUE 위반이 나도 흡수한다(예외 전파 안 함)")
    void 동시생성_위반_흡수() {
        given(walletBalanceRepository.existsByWalletAndCurrencyCode(wallet, CurrencyType.KRW))
                .willReturn(false);
        willThrow(new DataIntegrityViolationException("duplicate (wallet, currency)"))
                .given(walletBalanceRepository).saveAndFlush(any());

        assertThatCode(() -> writer.ensureBalanceRow(wallet, CurrencyType.KRW))
                .doesNotThrowAnyException();
    }
}