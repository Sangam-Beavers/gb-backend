package com.gb.admin.global.client;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 감사 로그 검색 필터. 모든 필드 nullable — 미지정 시 해당 조건 제외.
 */
public record AuditLogFilter(
        String userPublicId,
        String action,
        String status,
        LocalDateTime from,
        LocalDateTime to,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String ipAddress
) {
}
