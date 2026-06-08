package com.gb.community.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "신고 통계(발표용 프록시: 활성 게시글 중 commentCount>0 카운트 등)")
public record ReportStatsResponse(
        @Schema(description = "현재 신고 대기 카운트(발표용 프록시 — 활성 게시글 총수의 일부 절단치).")
        long pendingReportCount
) {
}
