package com.gb.admin.global.client;

import java.time.LocalDateTime;

public record AdminReportSummary(
        String postPublicId,
        String title,
        String authorPublicId,
        String authorNickname,
        long reportCount,
        String category,        // SPAM / ABUSE / ...
        LocalDateTime lastReportedAt
) {
}
