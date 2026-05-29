package com.gb.wallet.domain.transaction.repository;

import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 메인 그룹 결과의 (bankAccountId, lastTransferredAt) 쌍에 정확히 대응하는 REMITTANCE 행에서
 * 표시용 컬럼(amount/currencyCode/receiverName)을 가져오기 위한 Projection.
 *
 * <p>서로 다른 bank_account가 동일 timestamp를 갖는 희박한 충돌은 Service에서
 * (bankAccountId, createdAt) 정확 매칭으로 한 번 더 걸러낸다.
 *
 * <p>{@code receiverName}은 응답의 {@code account_holder}로 매핑된다 — bank_accounts 테이블엔
 * 보유자명 컬럼이 없고, 송금 시점에 transactions.receiver_name에 보유자명이 기록되기 때문.
 */
public interface RemittanceAmountProjection {

    Long getBankAccountId();

    BigDecimal getAmount();

    CurrencyType getCurrencyCode();

    String getReceiverName();

    LocalDateTime getCreatedAt();
}
