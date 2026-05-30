package com.gb.wallet.domain.transaction.repository;

import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link TransactionAuditLog} 저장소. 감사 로그는 append-only라 본 PR에서는 INSERT가 주 용도다.
 *
 * <p>조회 메서드는 멱등성 재요청 경로(ChargeServiceImpl#readPrior)에서 첫 응답의 {@code after_balance}를
 * 재현하기 위한 단건 조회 하나만 둔다(YAGNI — 그 외 조회는 필요해질 때 추가).
 */
public interface TransactionAuditLogRepository extends JpaRepository<TransactionAuditLog, Long> {

    /**
     * 거래의 최초 감사 로그를 조회한다. 멱등성 재요청 시 "충전 후 지갑 잔액"(= 당시 after_balance)을
     * 그대로 돌려주기 위함이다 — 두 번째 요청 시점엔 다른 거래로 현재 잔액이 변했을 수 있으므로
     * 현재 잔액이 아닌 최초 처리 당시의 after_balance를 재현해야 한다.
     *
     * <p>한 거래에 여러 로그가 쌓이는 미래 확장에 대비해 id 오름차순 첫 건(=최초 기록)을 명시적으로 고른다.
     */
    Optional<TransactionAuditLog> findFirstByTransaction_IdOrderByIdAsc(Long transactionId);
}