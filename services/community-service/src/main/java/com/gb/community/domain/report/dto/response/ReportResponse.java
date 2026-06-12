package com.gb.community.domain.report.dto.response;

import com.gb.community.domain.report.entity.Report;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneOffset;

@Schema(description = "신고 생성 응답")
public record ReportResponse(

        @Schema(description = "신고 publicId (UUID)", example = "550e8400-e29b-41d4-a716-446655440000")
        String publicId,

        @Schema(description = "신고 사유", example = "SPAM")
        String reason,

        @Schema(description = "신고 상태", example = "PENDING")
        String status,

        @Schema(description = "신고 생성 시각 (UTC ISO 8601)", example = "2026-06-11T10:00:00Z")
        String createdAt
) {

    public static ReportResponse from(Report r) {
        return new ReportResponse(
                r.getPublicId(),
                r.getReason().name(),
                r.getStatus().name(),
                r.getCreatedAt() != null
                        ? r.getCreatedAt().atOffset(ZoneOffset.UTC).toString().replace("+00:00", "Z")
                        : null
        );
    }
}
