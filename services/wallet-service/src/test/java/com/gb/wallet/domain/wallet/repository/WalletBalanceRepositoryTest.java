package com.gb.wallet.domain.wallet.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.WalletStatus;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link WalletBalanceRepository#findForUpdateByWalletAndCurrency} 검증.
 *
 * <p>H2(MySQL 호환 모드)에서 {@code SELECT … FOR UPDATE}가 NPE 없이 동작하고 (지갑, 통화)로 정확히
 * 한 행을 가려내는지 확인한다. 진짜 락 경합(동시 트랜잭션 직렬화)은 단일 스레드 @DataJpaTest로는 재현하기
 * 어려우므로 충전 통합/단위 테스트가 보완한다 — 여기서는 쿼리 정확성(필터링)을 본다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class WalletBalanceRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private WalletBalanceRepository repository;

    @Test
    @DisplayName("해당 (지갑, 통화) 잔액 행을 반환한다")
    void findForUpdate_정상_조회() {
        Wallet wallet = persistWallet("u1");
        persistBalance(wallet, CurrencyType.KRW, "1000000");
        persistBalance(wallet, CurrencyType.USD, "50");
        em.flush();
        em.clear();

        Optional<WalletBalance> krw = repository.findForUpdateByWalletAndCurrency(wallet, CurrencyType.KRW);

        assertThat(krw).isPresent();
        assertThat(krw.get().getCurrencyCode()).isEqualTo(CurrencyType.KRW);
        assertThat(krw.get().getBalance()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("같은 지갑이라도 통화 행이 없으면 empty(첫 충전 케이스)")
    void findForUpdate_통화행_없으면_empty() {
        Wallet wallet = persistWallet("u1");
        persistBalance(wallet, CurrencyType.KRW, "1000000");
        em.flush();
        em.clear();

        assertThat(repository.findForUpdateByWalletAndCurrency(wallet, CurrencyType.USD)).isEmpty();
    }

    @Test
    @DisplayName("다른 지갑의 같은 통화 행은 섞이지 않는다")
    void findForUpdate_다른지갑_제외() {
        Wallet w1 = persistWallet("u1");
        Wallet w2 = persistWallet("u2");
        persistBalance(w1, CurrencyType.KRW, "100");
        persistBalance(w2, CurrencyType.KRW, "999");
        em.flush();
        em.clear();

        assertThat(repository.findForUpdateByWalletAndCurrency(w1, CurrencyType.KRW))
                .get()
                .extracting(WalletBalance::getBalance)
                .satisfies(b -> assertThat((BigDecimal) b).isEqualByComparingTo("100"));
    }

    // ----- helpers -----

    private Wallet persistWallet(String userPublicId) {
        Wallet wallet = Wallet.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .status(WalletStatus.ACTIVE)
                .build();
        em.persist(wallet);
        return wallet;
    }

    private void persistBalance(Wallet wallet, CurrencyType currency, String amount) {
        em.persist(WalletBalance.builder()
                .wallet(wallet)
                .currencyCode(currency)
                .balance(new BigDecimal(amount))
                .build());
    }
}