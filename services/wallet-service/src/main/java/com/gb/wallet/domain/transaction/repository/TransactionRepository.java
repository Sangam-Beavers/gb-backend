package com.gb.wallet.domain.transaction.repository;

import com.gb.wallet.domain.transaction.entity.Transaction;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

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
}
