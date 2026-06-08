package com.gb.admin.global.client;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 금융 감사 로그 단건 — wallet /internal/admin/transaction-audit-logs 응답 모델.
 * 금액은 BigDecimal — 응답 직렬화 시점에 String 으로 변환된다.
 */
public record AdminAuditLogEntry(
        String auditLogPublicId,
        String transactionPublicId,
        String userPublicId,
        String action,
        BigDecimal amount,
        String currencyCode,
        BigDecimal beforeBalance,
        BigDecimal afterBalance,
        String status,
        String reason,
        String ipAddress,
        LocalDateTime createdAt
) {
}
