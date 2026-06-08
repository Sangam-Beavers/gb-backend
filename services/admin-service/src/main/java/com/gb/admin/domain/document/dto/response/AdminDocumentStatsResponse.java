package com.gb.admin.domain.document.dto.response;

import com.gb.admin.global.client.DocumentStats;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 문서 분석 통계")
public record AdminDocumentStatsResponse(
        @Schema(example = "348") long todayAnalyzed,
        @Schema(example = "332") long successCount,
        @Schema(example = "11") long failedCount,
        @Schema(example = "5") long partialCount
) {

    public static AdminDocumentStatsResponse from(DocumentStats s) {
        return new AdminDocumentStatsResponse(
                s.todayAnalyzed(), s.successCount(), s.failedCount(), s.partialCount()
        );
    }
}
