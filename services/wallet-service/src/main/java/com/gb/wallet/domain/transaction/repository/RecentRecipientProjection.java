package com.gb.wallet.domain.transaction.repository;

import java.time.LocalDateTime;

/**
 * "최근 송금 앱 사용자" 조회용 인터페이스 기반 Projection.
 *
 * <p>{@link TransactionRepository#findRecentInternalTransferRecipients}의 JPQL은
 * 수신자(wallet)별 그룹화 + MAX(createdAt)만 반환한다.
 * "가장 최근 송금의 통화 코드(last_currency_code)"는 GROUP BY와 함께 한 쿼리로 잡기 까다로워서
 * 이 Projection엔 포함하지 않고, Service 단에서 (receiverWalletId, lastTransferredAt)을
 * 키로 다시 조회해 채우는 방식으로 분리했다.
 */
public interface RecentRecipientProjection {

    Long getReceiverWalletId();

    LocalDateTime getLastTransferredAt();
}
