package com.gb.wallet.domain.wallet.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gb.wallet.domain.wallet.dto.response.WalletResponse;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.client.ExchangeRateClient;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.WalletStatus;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * WalletServiceImpl#createOrGetWallet 단위 테스트(이슈 #152).
 *
 * <p>이슈 #152의 새 메서드만 좁게 검증한다 — 기존 잔액 조회 등은 다른 테스트가 담당. 핵심 시나리오:
 * 신규 생성 시 KRW 0 잔액 1행이 함께 생긴다, 두 번째 호출은 멱등(저장 호출 없음), 동시 호출로
 * UNIQUE 위반이 나면 재조회로 흡수해 멱등을 지킨다.
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceImplCreateTest {

    @Mock private WalletRepository walletRepository;
    @Mock private WalletBalanceRepository walletBalanceRepository;
    @Mock private ExchangeRateClient exchangeRateClient;

    @InjectMocks private WalletServiceImpl walletService;

    private static final String USER_PUBLIC_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    @DisplayName("신규 사용자면 지갑 + KRW 0원 잔액 1행을 생성하고 단건 응답을 돌려준다")
    void createOrGetWallet_신규생성_KRW0잔액() {
        when(walletRepository.findByUserPublicId(USER_PUBLIC_ID)).thenReturn(Optional.empty());
        when(walletRepository.saveAndFlush(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.createOrGetWallet(USER_PUBLIC_ID);

        // 응답 단건: public_id는 UUID로 채워지고 status는 ACTIVE.
        assertThat(response.getWalletPublicId()).isNotBlank();
        assertThat(response.getStatus()).isEqualTo(WalletStatus.ACTIVE.name());

        // 지갑 INSERT
        ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).saveAndFlush(walletCaptor.capture());
        assertThat(walletCaptor.getValue().getUserPublicId()).isEqualTo(USER_PUBLIC_ID);
        assertThat(walletCaptor.getValue().getStatus()).isEqualTo(WalletStatus.ACTIVE);

        // KRW 0원 잔액 INSERT (lazy 정책 — 다른 통화는 첫 환전 시 생성)
        ArgumentCaptor<WalletBalance> balanceCaptor = ArgumentCaptor.forClass(WalletBalance.class);
        verify(walletBalanceRepository).save(balanceCaptor.capture());
        assertThat(balanceCaptor.getValue().getCurrencyCode()).isEqualTo(CurrencyType.KRW);
        assertThat(balanceCaptor.getValue().getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("이미 지갑이 있으면 그대로 반환하고 새로 만들지 않는다(멱등)")
    void createOrGetWallet_멱등_기존지갑_재사용() {
        Wallet existing = Wallet.builder()
                .publicId("existing-wallet-uuid")
                .userPublicId(USER_PUBLIC_ID)
                .status(WalletStatus.ACTIVE)
                .build();
        when(walletRepository.findByUserPublicId(USER_PUBLIC_ID)).thenReturn(Optional.of(existing));

        WalletResponse response = walletService.createOrGetWallet(USER_PUBLIC_ID);

        assertThat(response.getWalletPublicId()).isEqualTo("existing-wallet-uuid");
        verify(walletRepository, never()).saveAndFlush(any(Wallet.class));
        verify(walletBalanceRepository, never()).save(any(WalletBalance.class));
    }

    @Test
    @DisplayName("동시 호출로 UNIQUE 위반이 나면 재조회로 흡수해 멱등을 지킨다")
    void createOrGetWallet_동시생성_UNIQUE위반_재조회_흡수() {
        Wallet existingFromRace = Wallet.builder()
                .publicId("race-winner-uuid")
                .userPublicId(USER_PUBLIC_ID)
                .status(WalletStatus.ACTIVE)
                .build();
        // 1) 첫 조회 → empty (race 시작)
        // 2) saveAndFlush → DataIntegrityViolationException (UNIQUE 위반)
        // 3) catch 후 재조회 → 다른 트랜잭션이 먼저 만든 지갑 반환
        when(walletRepository.findByUserPublicId(USER_PUBLIC_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingFromRace));
        when(walletRepository.saveAndFlush(any(Wallet.class)))
                .thenThrow(new DataIntegrityViolationException("uk_wallets_user_public_id"));

        WalletResponse response = walletService.createOrGetWallet(USER_PUBLIC_ID);

        assertThat(response.getWalletPublicId()).isEqualTo("race-winner-uuid");
        verify(walletRepository, times(2)).findByUserPublicId(USER_PUBLIC_ID);
        verify(walletBalanceRepository, never()).save(any(WalletBalance.class));   // race 흡수 경로는 새 잔액 행 안 만듦
    }

    @Test
    @DisplayName("UNIQUE 위반 후에도 재조회가 empty면 원 예외를 다시 던진다(예외적 케이스)")
    void createOrGetWallet_UNIQUE위반_재조회도없음_원예외_재throw() {
        when(walletRepository.findByUserPublicId(USER_PUBLIC_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(walletRepository.saveAndFlush(any(Wallet.class)))
                .thenThrow(new DataIntegrityViolationException("uk_wallets_user_public_id"));

        assertThatThrownBy(() -> walletService.createOrGetWallet(USER_PUBLIC_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
