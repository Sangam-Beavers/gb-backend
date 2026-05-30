package com.gb.wallet.domain.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
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
 * {@link TransactionAuditLogRepository} 검증 — 저장(append-only)과 멱등성 재반환용 최초 로그 조회.
 * H2(MySQL 호환 모드) — application-test.yml이 datasource를 제공한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TransactionAuditLogRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private TransactionAuditLogRepository repository;

    @Test
    @DisplayName("save: 감사 로그가 INSERT되고 before/after 등 필드가 보존된다")
    void save_적재_및_필드보존() {
        Transaction tx = persistTransaction(persistWallet());

        TransactionAuditLog saved = repository.save(TransactionAuditLog.builder()
                .transaction(tx)
                .userPublicId("u1")
                .action("CHARGE")
                .amount(new BigDecimal("500000"))
                .currencyCode(CurrencyType.KRW)
                .beforeBalance(BigDecimal.ZERO)
                .afterBalance(new BigDecimal("500000"))
                .status(TransactionStatus.COMPLETED)
                .ipAddress("127.0.0.1")
                .build());
        em.flush();
        em.clear();

        TransactionAuditLog found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getAction()).isEqualTo("CHARGE");
        assertThat(found.getUserPublicId()).isEqualTo("u1");
        assertThat(found.getBeforeBalance()).isEqualByComparingTo("0");
        assertThat(found.getAfterBalance()).isEqualByComparingTo("500000");
        assertThat(found.getCurrencyCode()).isEqualTo(CurrencyType.KRW);
        assertThat(found.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(found.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(found.getTransaction().getId()).isEqualTo(tx.getId());
    }

    @Test
    @DisplayName("findFirstByTransaction_IdOrderByIdAsc: 거래의 최초(id 최소) 로그를 반환")
    void findFirst_최초로그() {
        Transaction tx = persistTransaction(persistWallet());
        TransactionAuditLog first = persistLog(tx, "0", "500000");
        persistLog(tx, "500000", "700000"); // 같은 거래에 후속 로그가 생겨도 최초 건이 잡혀야 함
        em.flush();
        em.clear();

        Optional<TransactionAuditLog> found = repository.findFirstByTransaction_IdOrderByIdAsc(tx.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(first.getId());
        assertThat(found.get().getAfterBalance()).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("findFirstByTransaction_IdOrderByIdAsc: 로그 없으면 empty")
    void findFirst_없으면_empty() {
        assertThat(repository.findFirstByTransaction_IdOrderByIdAsc(99_999L)).isEmpty();
    }

    // ----- helpers -----

    private Wallet persistWallet() {
        Wallet wallet = Wallet.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(UUID.randomUUID().toString())
                .status(WalletStatus.ACTIVE)
                .build();
        em.persist(wallet);
        return wallet;
    }

    private Transaction persistTransaction(Wallet wallet) {
        Transaction tx = Transaction.builder()
                .publicId(UUID.randomUUID().toString())
                .wallet(wallet)
                .type(TransactionType.CHARGE)
                .amount(new BigDecimal("500000"))
                .currencyCode(CurrencyType.KRW)
                .fee(BigDecimal.ZERO)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
        em.persist(tx);
        return tx;
    }

    private TransactionAuditLog persistLog(Transaction tx, String before, String after) {
        TransactionAuditLog log = TransactionAuditLog.builder()
                .transaction(tx)
                .userPublicId("u1")
                .action("CHARGE")
                .amount(new BigDecimal("500000"))
                .currencyCode(CurrencyType.KRW)
                .beforeBalance(new BigDecimal(before))
                .afterBalance(new BigDecimal(after))
                .status(TransactionStatus.COMPLETED)
                .build();
        em.persist(log);
        return log;
    }
}