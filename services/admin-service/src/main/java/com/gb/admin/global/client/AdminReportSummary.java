package com.gb.admin.global.client;

import java.time.LocalDateTime;

public record AdminReportSummary(
        String postPublicId,
        String title,
        String authorPublicId,
        String authorNickname,
        long reportCount,
        String reason,          // SPAM / ABUSE / FRAUD / SEXUAL / ETC
        String targetType,      // POST / COMMENT
        String status,          // PENDING / RESOLVED_DELETED / DISMISSED
        LocalDateTime lastReportedAt
) {
}
