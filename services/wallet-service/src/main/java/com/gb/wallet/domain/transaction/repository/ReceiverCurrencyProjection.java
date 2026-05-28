package com.gb.wallet.domain.transaction.repository;

import com.gb.wallet.global.common.enums.CurrencyType;
import java.time.LocalDateTime;

/**
 * "수신자별 가장 최근 송금의 통화 코드"를 IN-batch 한 번에 가져오기 위한 Projection.
 *
 * <p>1차 쿼리(메인) 결과인 (receiverWalletId, lastTransferredAt)을 그대로 사용해
 * 정확히 그 트랜잭션 행의 currency_code만 매칭한다.
 * 서로 다른 receiver가 같은 timestamp를 가지는 희박한 충돌을 막기 위해 Service에서
 * (receiverId, createdAt) 정합성 검증을 한 번 더 한다.
 */
public interface ReceiverCurrencyProjection {

    Long getReceiverWalletId();

    CurrencyType getCurrencyCode();

    LocalDateTime getCreatedAt();
}
