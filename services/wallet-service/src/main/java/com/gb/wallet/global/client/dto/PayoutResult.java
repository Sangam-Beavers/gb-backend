package com.gb.wallet.global.client.dto;

import java.math.BigDecimal;

/**
 * Mock 은행 {@code POST /api/v1/bank/transfers/payout} 응답.
 *
 * <p>현금화(외부 계좌 증액) 결과. {@code amount}는 본체가 환전 완료한 최종 외화 금액이며 Mock은 환율을 모른다.
 */
public record PayoutResult(
        String transactionId,
        String status,
        BigDecimal amount,
        String currencyCode,
        BigDecimal balanceAfter) {
}