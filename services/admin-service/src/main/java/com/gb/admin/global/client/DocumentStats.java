package com.gb.admin.global.client;

public record DocumentStats(
        long todayAnalyzed,
        long successCount,
        long failedCount,
        long partialCount
) {
}
