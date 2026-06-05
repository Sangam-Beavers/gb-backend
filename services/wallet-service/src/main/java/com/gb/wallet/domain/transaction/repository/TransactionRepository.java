package com.gb.wallet.domain.transaction.repository;

import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.global.common.enums.TransactionType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * 멱등성 키로 기존 거래를 조회한다. {@code idempotency_key}는 UNIQUE라 최대 1건이다.
     * 동일 키 재요청 시 새 거래를 만들지 않고 첫 거래(의 결과)를 재반환하기 위한 진입점이며,
     * 동시 요청 race에서 UNIQUE 위반 후 첫 거래를 재조회하는 데도 쓰인다(ChargeServiceImpl).
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /** 외부 노출 식별자(public_id)로 거래 단건 조회. 환전 내역 조회 등에서 사용. */
    Optional<Transaction> findByPublicId(String publicId);

    /**
     * 회원의 특정 유형 거래를 페이지로 조회한다. 환전 내역 목록({@code GET /api/v1/exchanges})에서
     * {@code type=EXCHANGE}로 호출하며, 정렬(최근순)은 {@link Pageable}로 받는다.
     * {@code wallet.userPublicId}로 본인 거래만 필터링한다(MSA 경계 — public_id 참조).
     */
    Page<Transaction> findByWallet_UserPublicIdAndType(
            String userPublicId, TransactionType type, Pageable pageable);

    /**
     * 회원의 모든 유형 거래(CHARGE/INTERNAL_TRANSFER/REMITTANCE/EXCHANGE)를 페이지로 조회한다.
     * 마이페이지 거래내역({@code GET /api/v1/wallets/me/transactions})에서 호출하며,
     * 정렬(최근순)은 {@link Pageable}로 받는다. {@code wallet.userPublicId}로 본인 거래만 필터링한다
     * (MSA 경계 — public_id 참조).
     *
     * <p><b>주의:</b> 본 메서드는 본인이 <b>송신자</b>인 거래만 반환한다(INTERNAL_TRANSFER 수신자
     * 거래는 별도 row가 없고 {@code receiverWallet} FK로만 연결되므로 누락). 마이페이지 거래내역은
     * 송수신을 모두 포함해야 하므로 {@link #findByMineSendingOrReceiving}를 사용한다.
     */
    Page<Transaction> findByWallet_UserPublicId(String userPublicId, Pageable pageable);

    /**
     * 회원이 <b>송신자 또는 수신자</b>인 모든 유형 거래를 페이지로 조회한다.
     * 마이페이지 거래내역({@code GET /api/v1/wallets/me/transactions})에서 호출한다.
     *
     * <p>INTERNAL_TRANSFER의 transactions 행은 송신자 1건만 INSERT되고 수신자는 {@code receiverWallet}
     * FK로만 연결된다. 본인이 수신자인 거래도 함께 보여주기 위해 송수신 양쪽을 OR로 조회한다.
     * 송수신 시점 본인이 어느 쪽이었는지(direction)는 Service에서 결정해 응답에 채운다.
     *
     * <p><b>LEFT JOIN 명시 이유:</b> {@code t.receiverWallet.userPublicId} 같은 path 표현은 JPQL에서
     * 묵시적 INNER JOIN이 되어 {@code receiverWallet=null}인 거래(CHARGE/REMITTANCE/EXCHANGE)는
     * OR 좌측이 참이어도 join 단계에서 row가 제거된다. alias로 명시적 LEFT JOIN을 잡아야 OR 양쪽 조건이
     * 의도대로 적용된다.
     */
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN t.receiverWallet rw
            WHERE t.wallet.userPublicId = :userPublicId
               OR rw.userPublicId = :userPublicId
            """)
    Page<Transaction> findByMineSendingOrReceiving(
            @Param("userPublicId") String userPublicId, Pageable pageable);

    /**
     * {@code idempotency_key}가 지정된 prefix로 시작하는 거래를 페이지로 조회한다.
     *
     * <p>정기 송금 회차 이력 조회({@code GET /api/v1/transfers/scheduled/{id}/history})에서 사용한다 —
     * 스케줄러가 회차마다 {@code "scheduled:{public_id}:{nextRunDate}"} 형태로 idempotency_key를 박으므로
     * (이중송금 방지의 SSOT는 실행 예정일 nextRunDate — today 아님), {@code "scheduled:{public_id}:"} prefix로
     * 검색하면 해당 정기 송금의 모든 회차가 나온다.
     *
     * <p>UNIQUE 인덱스의 prefix 검색이라 RDBMS가 인덱스를 활용한다 (LIKE 'prefix%' 패턴).
     * 정렬은 {@link Pageable}에 위임 (현 정책: created_at DESC = executed_at DESC).
     */
    Page<Transaction> findByIdempotencyKeyStartingWith(String prefix, Pageable pageable);

    /**
     * 내가 송신자인 INTERNAL_TRANSFER(COMPLETED) 중, 수신자(receiver wallet)별로 가장 최근 송금
     * 한 건씩 골라 최근순으로 N건을 반환한다. {@code N}은 {@link Pageable#getPageSize()}로 제어한다.
     *
     * <p>가장 최근 송금의 통화(last_currency_code)는 GROUP BY로 한번에 잡기 어려워 Service에서
     * {@link #findCurrencyCodesForLatestTransfers}로 별도 IN-batch 조회해 채운다.
     */
    @Query("""
            SELECT t.receiverWallet.id AS receiverWalletId,
                   MAX(t.createdAt)    AS lastTransferredAt
            FROM Transaction t
            WHERE t.wallet.id = :senderWalletId
              AND t.type = com.gb.wallet.global.common.enums.TransactionType.INTERNAL_TRANSFER
              AND t.status = com.gb.wallet.global.common.enums.TransactionStatus.COMPLETED
              AND t.receiverWallet IS NOT NULL
            GROUP BY t.receiverWallet.id
            ORDER BY MAX(t.createdAt) DESC
            """)
    List<RecentRecipientProjection> findRecentInternalTransferRecipients(
            @Param("senderWalletId") Long senderWalletId,
            Pageable pageable);

    /**
     * 위 조회 결과의 (receiverWalletId, lastTransferredAt) 쌍에 정확히 대응하는 행의
     * currency_code를 한 쿼리(IN-batch)로 가져온다.
     *
     * <p>WHERE는 IN x2로 후보를 좁히고, 서로 다른 receiver가 동일 timestamp를 갖는 희박한 충돌은
     * Service에서 (receiverId, createdAt) 정확 매칭으로 한 번 더 걸러낸다.
     */
    @Query("""
            SELECT t.receiverWallet.id AS receiverWalletId,
                   t.currencyCode      AS currencyCode,
                   t.createdAt         AS createdAt
            FROM Transaction t
            WHERE t.wallet.id = :senderWalletId
              AND t.type = com.gb.wallet.global.common.enums.TransactionType.INTERNAL_TRANSFER
              AND t.status = com.gb.wallet.global.common.enums.TransactionStatus.COMPLETED
              AND t.receiverWallet.id IN :receiverWalletIds
              AND t.createdAt IN :timestamps
            """)
    List<ReceiverCurrencyProjection> findCurrencyCodesForLatestTransfers(
            @Param("senderWalletId") Long senderWalletId,
            @Param("receiverWalletIds") List<Long> receiverWalletIds,
            @Param("timestamps") List<LocalDateTime> timestamps);

    /**
     * 내가 송신자인 REMITTANCE(COMPLETED) 중, bank_account별로 가장 최근 송금 한 건씩
     * 골라 최근순으로 N건을 반환한다. {@code N}은 {@link Pageable#getPageSize()}로 제어한다.
     *
     * <p>표시용 컬럼(amount/currencyCode/receiverName)은 {@link #findAmountsForLatestRemittances}
     * 보조 IN-batch로 채운다 — INTERNAL_TRANSFER 최근 조회와 동일한 두-단계 패턴.
     */
    @Query("""
            SELECT t.bankAccountId AS bankAccountId,
                   MAX(t.createdAt) AS lastTransferredAt
            FROM Transaction t
            WHERE t.wallet.id = :senderWalletId
              AND t.type = com.gb.wallet.global.common.enums.TransactionType.REMITTANCE
              AND t.status = com.gb.wallet.global.common.enums.TransactionStatus.COMPLETED
              AND t.bankAccountId IS NOT NULL
            GROUP BY t.bankAccountId
            ORDER BY MAX(t.createdAt) DESC
            """)
    List<RecentAccountProjection> findRecentRemittanceAccounts(
            @Param("senderWalletId") Long senderWalletId,
            Pageable pageable);

    /**
     * 위 결과의 (bankAccountId, lastTransferredAt) 쌍에 정확히 대응하는 행에서
     * amount/currencyCode/receiverName을 한 쿼리(IN-batch)로 가져온다.
     * 동일 timestamp 충돌은 Service에서 (bankAccountId, createdAt) 정확 매칭으로 한 번 더 걸러낸다.
     */
    @Query("""
            SELECT t.bankAccountId AS bankAccountId,
                   t.amount        AS amount,
                   t.currencyCode  AS currencyCode,
                   t.receiverName  AS receiverName,
                   t.createdAt     AS createdAt
            FROM Transaction t
            WHERE t.wallet.id = :senderWalletId
              AND t.type = com.gb.wallet.global.common.enums.TransactionType.REMITTANCE
              AND t.status = com.gb.wallet.global.common.enums.TransactionStatus.COMPLETED
              AND t.bankAccountId IN :bankAccountIds
              AND t.createdAt IN :timestamps
            """)
    List<RemittanceAmountProjection> findAmountsForLatestRemittances(
            @Param("senderWalletId") Long senderWalletId,
            @Param("bankAccountIds") List<Long> bankAccountIds,
            @Param("timestamps") List<LocalDateTime> timestamps);
}
