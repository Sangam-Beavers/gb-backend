package com.gb.wallet.global.client.dto;

import java.math.BigDecimal;

/**
 * Mock 은행 {@code POST /api/v1/bank/transfers/withdrawal} 응답.
 *
 * <p>충전(외부 계좌 차감) 결과. {@code balanceAfter}는 외부 계좌 잔액일 뿐 본체 지갑 잔액과 무관하다.
 * Mock 통신은 string 십진수지만 본체 내부는 {@link BigDecimal}로 다룬다.
 */
public record WithdrawalResult(
        String transactionId,
        String status,
        BigDecimal amount,
        String currencyCode,
        BigDecimal balanceAfter) {
}