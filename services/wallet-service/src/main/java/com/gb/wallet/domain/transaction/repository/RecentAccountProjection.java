package com.gb.wallet.domain.transaction.repository;

import java.time.LocalDateTime;

/**
 * "최근 송금 계좌" 메인 그룹 조회 Projection.
 * (bankAccountId 기준 그룹, MAX(createdAt)) 두 컬럼만. 나머지 표시용 컬럼(amount/currency/receiverName)은
 * {@link RemittanceAmountProjection}으로 별도 IN-batch 조회해 결합한다 — 최근 앱 사용자 조회의 패턴과 동일.
 */
public interface RecentAccountProjection {

    Long getBankAccountId();

    LocalDateTime getLastTransferredAt();
}
