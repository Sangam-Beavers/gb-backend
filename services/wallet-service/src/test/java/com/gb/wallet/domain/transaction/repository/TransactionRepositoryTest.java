package com.gb.wallet.domain.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link TransactionRepository}의 두 쿼리(메인 GROUP BY + IN-batch currency) 검증.
 *
 * <p>인메모리 H2(MySQL 호환 모드)에서 돌린다. {@code application-test.yml}이 datasource를 제공하므로
 * {@code @AutoConfigureTestDatabase(replace = NONE)}로 자동 교체를 막는다.
 *
 * <p>{@code created_at}은 native SQL UPDATE로 직접 박는다. 이유:
 * <ul>
 *   <li>같은 Gradle test JVM에서 {@code @SpringBootTest}가 함께 도는 경우
 *       {@code AuditingEntityListener}가 활성화돼 {@code @PrePersist}에서 {@code @CreatedDate}를
 *       {@code now()}로 덮어쓴다. reflection으로 미리 세팅해도 무용지물.</li>
 *   <li>JPA의 {@code updatable=false}는 Hibernate가 UPDATE SQL을 만들지 못하게 막을 뿐
 *       native SQL UPDATE는 그대로 통과한다.</li>
 * </ul>
 * 즉 auditing 활성 여부와 무관하게 안전한 방식.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TransactionRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TransactionRepository transactionRepository;

    private Wallet sender;
    private Wallet linhWallet;
    private Wallet mariaWallet;
    private Wallet hieuWallet;

    // 시각은 일 단위로 충분히 벌려서 정렬·필터링 의도를 분명히 한다.
    private static final LocalDateTime T_LINH_OLD = LocalDateTime.of(2026, 5, 21, 10, 0);
    private static final LocalDateTime T_MARIA    = LocalDateTime.of(2026, 5, 22, 10, 0);
    private static final LocalDateTime T_HIEU     = LocalDateTime.of(2026, 5, 23, 10, 0);
    private static final LocalDateTime T_LINH_MID = LocalDateTime.of(2026, 5, 24, 10, 0);
    private static final LocalDateTime T_LINH_NEW = LocalDateTime.of(2026, 5, 25, 10, 0);
    private static final LocalDateTime T_FAILED   = LocalDateTime.of(2026, 5, 26, 10, 0);
    private static final LocalDateTime T_EXCHANGE = LocalDateTime.of(2026, 5, 26, 11, 0);
    private static final LocalDateTime T_NULL_RX  = LocalDateTime.of(2026, 5, 26, 12, 0);

    @BeforeEach
    void setUp() {
        sender      = persistWallet("sender-uuid");
        linhWallet  = persistWallet("11111111-1111-1111-1111-111111111111");
        mariaWallet = persistWallet("22222222-2222-2222-2222-222222222222");
        hieuWallet  = persistWallet("33333333-3333-3333-3333-333333333333");

        // Linh 3건: 가장 최근(T_LINH_NEW) 통화가 KRW가 되도록. 이전 건은 VND, USD.
        persistTransfer(sender, linhWallet,  CurrencyType.VND, TransactionType.INTERNAL_TRANSFER, TransactionStatus.COMPLETED, T_LINH_OLD);
        persistTransfer(sender, linhWallet,  CurrencyType.USD, TransactionType.INTERNAL_TRANSFER, TransactionStatus.COMPLETED, T_LINH_MID);
        persistTransfer(sender, linhWallet,  CurrencyType.KRW, TransactionType.INTERNAL_TRANSFER, TransactionStatus.COMPLETED, T_LINH_NEW);
        // Maria 1건(가장 옛날), Hieu 1건(중간).
        persistTransfer(sender, mariaWallet, CurrencyType.KRW, TransactionType.INTERNAL_TRANSFER, TransactionStatus.COMPLETED, T_MARIA);
        persistTransfer(sender, hieuWallet,  CurrencyType.KRW, TransactionType.INTERNAL_TRANSFER, TransactionStatus.COMPLETED, T_HIEU);

        // 필터 검증용 노이즈 — 모두 결과에 나오면 안 된다.
        persistTransfer(sender, linhWallet,  CurrencyType.KRW, TransactionType.INTERNAL_TRANSFER, TransactionStatus.FAILED,    T_FAILED);   // status 필터
        persistTransfer(sender, mariaWallet, CurrencyType.KRW, TransactionType.EXCHANGE,          TransactionStatus.COMPLETED, T_EXCHANGE); // type 필터
        persistTransfer(sender, null,        CurrencyType.KRW, TransactionType.INTERNAL_TRANSFER, TransactionStatus.COMPLETED, T_NULL_RX);  // receiverWallet IS NOT NULL 필터

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("수신자별 최신 송금 1건만, 최근순으로, 노이즈 거래(FAILED/EXCHANGE/receiverNull)는 제외돼 반환된다")
    void findRecentInternalTransferRecipients_정상_조회() {
        List<RecentRecipientProjection> result = transactionRepository
                .findRecentInternalTransferRecipients(sender.getId(), PageRequest.of(0, 10));

        assertThat(result)
                .as("Linh→Hieu→Maria 3명만 lastTransferredAt DESC 순서로")
                .extracting(RecentRecipientProjection::getReceiverWalletId,
                            RecentRecipientProjection::getLastTransferredAt)
                .containsExactly(
                        tuple(linhWallet.getId(),  T_LINH_NEW),
                        tuple(hieuWallet.getId(),  T_HIEU),
                        tuple(mariaWallet.getId(), T_MARIA));
    }

    @Test
    @DisplayName("Linh의 가장 최근 송금 통화는 KRW(T_LINH_NEW) — 이전 VND/USD가 아님")
    void findCurrencyCodesForLatestTransfers_Linh_가장_최근_통화는_KRW() {
        List<Long> receiverIds      = List.of(linhWallet.getId(), hieuWallet.getId(), mariaWallet.getId());
        List<LocalDateTime> stamps  = List.of(T_LINH_NEW, T_HIEU, T_MARIA);

        List<ReceiverCurrencyProjection> result = transactionRepository
                .findCurrencyCodesForLatestTransfers(sender.getId(), receiverIds, stamps);

        assertThat(result)
                .as("(receiverWalletId, createdAt, currencyCode) 세 항목이 기대값과 정확 매칭")
                .extracting(ReceiverCurrencyProjection::getReceiverWalletId,
                            ReceiverCurrencyProjection::getCreatedAt,
                            ReceiverCurrencyProjection::getCurrencyCode)
                .containsExactlyInAnyOrder(
                        tuple(linhWallet.getId(),  T_LINH_NEW, CurrencyType.KRW),
                        tuple(hieuWallet.getId(),  T_HIEU,     CurrencyType.KRW),
                        tuple(mariaWallet.getId(), T_MARIA,    CurrencyType.KRW));
    }

    @Test
    @DisplayName("REMITTANCE: bank_account별 최신 1건씩 최근순 + 노이즈(FAILED/IT/bankAccountId NULL) 제외")
    void findRecentRemittanceAccounts_정상_조회() {
        // REMITTANCE는 receiverWallet=null, bankAccountId=Long으로 분리되므로 별도 데이터를 추가한다.
        // 기존 @BeforeEach가 만들어 둔 INTERNAL_TRANSFER 거래는 type 필터로 자동 제외된다.
        long BANK_ACCOUNT_A = 101L;
        long BANK_ACCOUNT_B = 102L;
        LocalDateTime T_A_OLD = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime T_A_NEW = LocalDateTime.of(2026, 6, 3, 10, 0); // A의 최신 (KRW 200000)
        LocalDateTime T_B     = LocalDateTime.of(2026, 6, 2, 10, 0); // B의 유일
        LocalDateTime T_FAILED   = LocalDateTime.of(2026, 6, 4, 10, 0);
        LocalDateTime T_NULL_BA  = LocalDateTime.of(2026, 6, 5, 10, 0);

        persistRemittance(sender, BANK_ACCOUNT_A, new java.math.BigDecimal("100000"), CurrencyType.KRW, "김민수", TransactionStatus.COMPLETED, T_A_OLD);
        persistRemittance(sender, BANK_ACCOUNT_A, new java.math.BigDecimal("200000"), CurrencyType.KRW, "김민수", TransactionStatus.COMPLETED, T_A_NEW);
        persistRemittance(sender, BANK_ACCOUNT_B, new java.math.BigDecimal("50"),     CurrencyType.USD, "Nguyen", TransactionStatus.COMPLETED, T_B);
        // 노이즈: FAILED, bankAccountId NULL — 결과에서 제외돼야 함
        persistRemittance(sender, BANK_ACCOUNT_A, new java.math.BigDecimal("999"), CurrencyType.KRW, "X", TransactionStatus.FAILED,    T_FAILED);
        persistRemittance(sender, null,           new java.math.BigDecimal("999"), CurrencyType.KRW, "X", TransactionStatus.COMPLETED, T_NULL_BA);
        em.flush();
        em.clear();

        List<RecentAccountProjection> result = transactionRepository
                .findRecentRemittanceAccounts(sender.getId(), PageRequest.of(0, 10));

        assertThat(result)
                .as("A→B 순서로 (A는 T_A_NEW가 그룹 대표), 노이즈 제외")
                .extracting(RecentAccountProjection::getBankAccountId,
                            RecentAccountProjection::getLastTransferredAt)
                .containsExactly(
                        tuple(BANK_ACCOUNT_A, T_A_NEW),
                        tuple(BANK_ACCOUNT_B, T_B));
    }

    @Test
    @DisplayName("REMITTANCE: 최신 송금의 amount/currency/receiverName이 IN-batch로 정확 매칭")
    void findAmountsForLatestRemittances_정상_조회() {
        long BANK_ACCOUNT_A = 201L;
        long BANK_ACCOUNT_B = 202L;
        LocalDateTime T_A_OLD = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime T_A_NEW = LocalDateTime.of(2026, 6, 3, 10, 0);
        LocalDateTime T_B     = LocalDateTime.of(2026, 6, 2, 10, 0);

        // A에 두 건(OLD 100000 USD / NEW 200000 KRW), B에 한 건(50 USD).
        // 매칭 대상은 NEW 한 건만이어야 함 (이전 OLD의 USD가 잘못 매칭되면 안 됨).
        persistRemittance(sender, BANK_ACCOUNT_A, new java.math.BigDecimal("100000"), CurrencyType.USD, "OldHolder", TransactionStatus.COMPLETED, T_A_OLD);
        persistRemittance(sender, BANK_ACCOUNT_A, new java.math.BigDecimal("200000"), CurrencyType.KRW, "김민수",     TransactionStatus.COMPLETED, T_A_NEW);
        persistRemittance(sender, BANK_ACCOUNT_B, new java.math.BigDecimal("50"),     CurrencyType.USD, "Nguyen",    TransactionStatus.COMPLETED, T_B);
        em.flush();
        em.clear();

        List<RemittanceAmountProjection> result = transactionRepository
                .findAmountsForLatestRemittances(
                        sender.getId(),
                        List.of(BANK_ACCOUNT_A, BANK_ACCOUNT_B),
                        List.of(T_A_NEW, T_B));

        assertThat(result)
                .as("(bankAccountId, createdAt, amount(스케일4), currencyCode, receiverName)이 1:1")
                .extracting(RemittanceAmountProjection::getBankAccountId,
                            RemittanceAmountProjection::getCreatedAt,
                            r -> r.getAmount().setScale(4).toPlainString(),
                            RemittanceAmountProjection::getCurrencyCode,
                            RemittanceAmountProjection::getReceiverName)
                .containsExactlyInAnyOrder(
                        tuple(BANK_ACCOUNT_A, T_A_NEW, "200000.0000", CurrencyType.KRW, "김민수"),
                        tuple(BANK_ACCOUNT_B, T_B,     "50.0000",     CurrencyType.USD, "Nguyen"));
    }

    @Test
    @DisplayName("findByIdempotencyKey: 멱등성 키로 거래 단건 조회, 없으면 empty")
    void findByIdempotencyKey_조회() {
        Transaction charge = Transaction.builder()
                .publicId(UUID.randomUUID().toString())
                .wallet(sender)
                .type(TransactionType.CHARGE)
                .amount(new BigDecimal("500000"))
                .currencyCode(CurrencyType.KRW)
                .fee(BigDecimal.ZERO)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey("known-key-123")
                .build();
        em.persist(charge);
        em.flush();
        em.clear();

        assertThat(transactionRepository.findByIdempotencyKey("known-key-123"))
                .isPresent()
                .get()
                .extracting(Transaction::getPublicId)
                .isEqualTo(charge.getPublicId());
        assertThat(transactionRepository.findByIdempotencyKey("no-such-key")).isEmpty();
    }

    @Test
    @DisplayName("findByWallet_UserPublicId(C): 본인 전 유형(상태 무관) 거래만, Pageable 정렬(최근순)로 조회")
    void findByWallet_UserPublicId_전유형_정렬_본인필터() {
        Wallet userA = persistWallet("cuser-aaaa");
        Wallet userB = persistWallet("cuser-bbbb");

        // userA: 서로 다른 유형/상태 3건 — created_at 으로 순서를 통제한다.
        persistTransfer(userA, null, CurrencyType.KRW, TransactionType.CHARGE,
                TransactionStatus.COMPLETED, LocalDateTime.of(2026, 6, 10, 9, 0));
        persistTransfer(userA, null, CurrencyType.KRW, TransactionType.EXCHANGE,
                TransactionStatus.COMPLETED, LocalDateTime.of(2026, 6, 11, 9, 0));
        persistTransfer(userA, null, CurrencyType.KRW, TransactionType.REMITTANCE,
                TransactionStatus.FAILED, LocalDateTime.of(2026, 6, 12, 9, 0)); // FAILED도 필터 없이 포함
        // userB: 다른 사용자 — 결과에 섞이면 안 됨
        persistTransfer(userB, null, CurrencyType.KRW, TransactionType.CHARGE,
                TransactionStatus.COMPLETED, LocalDateTime.of(2026, 6, 13, 9, 0));
        em.flush();
        em.clear();

        Page<Transaction> page = transactionRepository.findByWallet_UserPublicId(
                "cuser-aaaa", PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).as("userA의 3건만(타 user 제외)").isEqualTo(3L);
        assertThat(page.getContent())
                .as("최근순 + 전 유형/상태 포함(유형·상태 필터 없음)")
                .extracting(Transaction::getType)
                .containsExactly(TransactionType.REMITTANCE, TransactionType.EXCHANGE, TransactionType.CHARGE);
    }

    @Test
    @DisplayName("findByWallet_UserPublicIdAndType(EXCHANGE): 본인 EXCHANGE만 최근순 — 타 유형(CHARGE/REMITTANCE)·타 user 제외")
    void findByWallet_UserPublicIdAndType_EXCHANGE_필터_정렬() {
        Wallet userA = persistWallet("exuser-aaaa");
        Wallet userB = persistWallet("exuser-bbbb");

        // userA EXCHANGE 2건 — created_at으로 순서를 통제(둘 다 결과에 최근순으로 나와야 함)
        persistTransfer(userA, null, CurrencyType.USD, TransactionType.EXCHANGE,
                TransactionStatus.COMPLETED, LocalDateTime.of(2026, 6, 20, 9, 0));
        persistTransfer(userA, null, CurrencyType.KRW, TransactionType.EXCHANGE,
                TransactionStatus.COMPLETED, LocalDateTime.of(2026, 6, 21, 9, 0)); // 최신
        // userA 노이즈: 타 유형 — type 필터로 제외돼야 함
        persistTransfer(userA, null, CurrencyType.KRW, TransactionType.CHARGE,
                TransactionStatus.COMPLETED, LocalDateTime.of(2026, 6, 22, 9, 0));
        persistTransfer(userA, null, CurrencyType.KRW, TransactionType.REMITTANCE,
                TransactionStatus.COMPLETED, LocalDateTime.of(2026, 6, 23, 9, 0));
        // userB EXCHANGE — 타 user라 제외돼야 함
        persistTransfer(userB, null, CurrencyType.KRW, TransactionType.EXCHANGE,
                TransactionStatus.COMPLETED, LocalDateTime.of(2026, 6, 24, 9, 0));
        em.flush();
        em.clear();

        Page<Transaction> page = transactionRepository.findByWallet_UserPublicIdAndType(
                "exuser-aaaa", TransactionType.EXCHANGE,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).as("userA의 EXCHANGE 2건만(타 유형·타 user 제외)").isEqualTo(2L);
        assertThat(page.getContent())
                .as("결과는 모두 EXCHANGE 타입")
                .extracting(Transaction::getType)
                .containsOnly(TransactionType.EXCHANGE);
        assertThat(page.getContent())
                .as("최근순(2026-06-21 KRW → 2026-06-20 USD)")
                .extracting(Transaction::getCurrencyCode)
                .containsExactly(CurrencyType.KRW, CurrencyType.USD);
    }

    // ----- helpers -----

    private Wallet persistWallet(String userPublicId) {
        Wallet w = Wallet.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .status(WalletStatus.ACTIVE)
                .build();
        em.persist(w);
        return w;
    }

    /**
     * 트랜잭션 1건을 영속화한 뒤 native SQL UPDATE로 created_at을 지정 시각으로 덮어쓴다.
     * (JPA 경로로는 auditing/updatable=false 때문에 통제가 어려움 — 클래스 주석 참고)
     */
    private void persistTransfer(Wallet senderWallet, Wallet receiverWallet, CurrencyType currency,
                                 TransactionType type, TransactionStatus status,
                                 LocalDateTime createdAt) {
        Transaction t = Transaction.builder()
                .publicId(UUID.randomUUID().toString())
                .wallet(senderWallet)
                .type(type)
                .amount(BigDecimal.ONE)
                .currencyCode(currency)
                .fee(BigDecimal.ZERO)
                .status(status)
                .idempotencyKey(UUID.randomUUID().toString())
                .receiverWallet(receiverWallet)
                .build();
        em.persist(t); // IDENTITY라 INSERT 즉시 실행되어 t.getId() 채워짐
        em.getEntityManager()
                .createNativeQuery("UPDATE transactions SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, t.getId())
                .executeUpdate();
    }

    /**
     * REMITTANCE용 헬퍼: receiverWallet=null, bankAccountId(raw Long) + receiverName + amount/currency 지정.
     * Transaction.bankAccountId는 현재 원시 Long(@ManyToOne 마이그레이션 전)이라 실제 bank_accounts 행
     * 없이도 임의 Long으로 안전하게 사용 가능.
     */
    private void persistRemittance(Wallet senderWallet, Long bankAccountId, BigDecimal amount,
                                   CurrencyType currency, String receiverName,
                                   TransactionStatus status, LocalDateTime createdAt) {
        Transaction t = Transaction.builder()
                .publicId(UUID.randomUUID().toString())
                .wallet(senderWallet)
                .type(TransactionType.REMITTANCE)
                .amount(amount)
                .currencyCode(currency)
                .fee(BigDecimal.ZERO)
                .status(status)
                .idempotencyKey(UUID.randomUUID().toString())
                .bankAccountId(bankAccountId)
                .receiverName(receiverName)
                .build();
        em.persist(t);
        em.getEntityManager()
                .createNativeQuery("UPDATE transactions SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, t.getId())
                .executeUpdate();
    }
}
