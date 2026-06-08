package com.gb.admin.global.client;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 관리자 거래 로그의 단건 요약. 금액은 BigDecimal — 응답 직렬화 시점에 String으로 변환된다(conventions §0).
 */
public record AdminTransactionSummary(
        String transactionPublicId,
        String userPublicId,
        String userName,
        String type,          // INTERNAL_TRANSFER / REMITTANCE / EXCHANGE / CHARGE / PAYOUT
        BigDecimal amount,
        String currencyCode,
        String status,        // COMPLETED / PENDING / FAILED / CANCELLED
        String riskLevel,     // LOW / MEDIUM / HIGH
        LocalDateTime executedAt
) {
}
