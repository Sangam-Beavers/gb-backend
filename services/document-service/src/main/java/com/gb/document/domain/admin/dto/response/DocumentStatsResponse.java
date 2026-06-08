package com.gb.document.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "AI 문서 분석 통계")
public record DocumentStatsResponse(
        long todayAnalyzed,
        long successCount,
        long failedCount,
        long partialCount,
        @Schema(description = "LOW/MEDIUM/HIGH 카운트.")
        Map<String, Long> byRiskLevel,
        @Schema(description = "AI 분석 성공률 0.0~1.0.", example = "0.9920")
        String aiAnalysisSuccessRate
) {
}
