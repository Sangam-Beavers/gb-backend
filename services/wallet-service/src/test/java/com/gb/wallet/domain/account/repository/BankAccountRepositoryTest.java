package com.gb.wallet.domain.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link BankAccountRepository}의 등록 흐름용 메서드 검증.
 *
 * <p>중복 등록 판단(existsBy…)과 첫 계좌 여부 판단(countBy…)이 활성 상태/사용자 구분을 정확히
 * 반영하는지 확인한다. H2(MySQL 호환 모드) — application-test.yml이 datasource를 제공하므로
 * {@code @AutoConfigureTestDatabase(replace = NONE)}로 자동 교체를 막는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class BankAccountRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private BankAccountRepository repository;

    private static final String USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ACCOUNT_NUMBER = "1234567890";

    private Bank kbBank;
    private Bank shinhanBank;

    @BeforeEach
    void setUp() {
        kbBank = persistBank("004", "KB국민은행");
        shinhanBank = persistBank("088", "신한은행");
    }

    @Test
    @DisplayName("existsBy…isActiveTrue: 활성 계좌 있으면 true, 비활성(soft-delete)이면 false")
    void existsBy_활성_여부_반영() {
        persistAccount(USER_A, kbBank, ACCOUNT_NUMBER, true, true);
        em.flush();
        em.clear();

        boolean exists = repository
                .existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(USER_A, "004", ACCOUNT_NUMBER);
        assertThat(exists).as("같은 회원·은행·계좌번호의 활성 레코드 → true").isTrue();

        // 활성을 비활성으로 바꾸면 같은 조건에 false
        em.getEntityManager()
                .createNativeQuery("UPDATE bank_accounts SET is_active = false WHERE user_public_id = ?1")
                .setParameter(1, USER_A)
                .executeUpdate();
        em.clear();

        boolean afterDeactivation = repository
                .existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(USER_A, "004", ACCOUNT_NUMBER);
        assertThat(afterDeactivation).as("비활성 레코드는 중복 검사에서 제외").isFalse();
    }

    @Test
    @DisplayName("existsBy…: 다른 사용자/다른 은행/다른 계좌번호는 false")
    void existsBy_다른_조건은_false() {
        persistAccount(USER_A, kbBank, ACCOUNT_NUMBER, true, true);
        em.flush();
        em.clear();

        assertThat(repository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_B, "004", ACCOUNT_NUMBER)).as("다른 사용자").isFalse();
        assertThat(repository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_A, "088", ACCOUNT_NUMBER)).as("다른 은행").isFalse();
        assertThat(repository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_A, "004", "9999999999")).as("다른 계좌번호").isFalse();
    }

    @Test
    @DisplayName("countBy…isActiveTrue: 활성 계좌만 카운트되고, 다른 사용자 것은 제외된다")
    void countBy_활성_사용자별() {
        // USER_A 활성 2건 + 비활성 1건 → count = 2
        persistAccount(USER_A, kbBank,      "1111111111", true,  true);
        persistAccount(USER_A, shinhanBank, "2222222222", false, true);
        persistAccount(USER_A, kbBank,      "3333333333", false, false); // 비활성 — 제외
        // USER_B는 다른 사용자 — 카운트에 잡히면 안 됨
        persistAccount(USER_B, kbBank,      "4444444444", true,  true);
        em.flush();
        em.clear();

        long countA = repository.countByUserPublicIdAndIsActiveTrue(USER_A);
        long countB = repository.countByUserPublicIdAndIsActiveTrue(USER_B);
        long countUnknown = repository.countByUserPublicIdAndIsActiveTrue("nobody");

        assertThat(countA).as("USER_A 활성 2건").isEqualTo(2L);
        assertThat(countB).as("USER_B 활성 1건").isEqualTo(1L);
        assertThat(countUnknown).as("없는 사용자").isZero();
    }

    // ----- helpers -----

    private Bank persistBank(String code, String name) {
        Bank bank = Bank.builder()
                .code(code)
                .name(name)
                .country("KR")
                .isDomestic(true)
                .isActive(true)
                .build();
        em.persist(bank);
        return bank;
    }

    private BankAccount persistAccount(String userPublicId, Bank bank, String accountNumber,
                                       boolean isPrimary, boolean isActive) {
        BankAccount account = BankAccount.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .bank(bank)
                .accountNumber(accountNumber)
                .mockAccountToken(UUID.randomUUID().toString())
                .isVirtual(false)
                .isPrimary(isPrimary)
                .isActive(isActive)
                .build();
        em.persist(account);
        return account;
    }
}