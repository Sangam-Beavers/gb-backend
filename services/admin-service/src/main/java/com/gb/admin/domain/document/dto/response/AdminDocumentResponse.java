package com.gb.admin.domain.document.dto.response;

import com.gb.admin.global.client.AdminDocumentSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "AI 문서 분석 단건")
public record AdminDocumentResponse(
        @Schema(example = "dddddddd-0001-0000-0000-000000000001") String documentPublicId,
        @Schema(example = "11111111-1111-1111-1111-111111111111") String userPublicId,
        @Schema(example = "Nguyen Thi Linh") String userName,
        @Schema(description = "분석 문서 타입.", example = "LABOR_CONTRACT",
                allowableValues = {"LABOR_CONTRACT", "PAYSLIP", "EMPLOYMENT_CONTRACT"})
        String analysisDocumentType,
        @Schema(example = "VI") String language,
        @Schema(description = "리스크 레벨.", example = "MEDIUM",
                allowableValues = {"LOW", "MEDIUM", "HIGH"})
        String overallRiskLevel,
        @Schema(example = "변호사 상담 광고 클릭") String followUpAction,
        @Schema(example = "2026-06-07T09:12:00Z") LocalDateTime analyzedAt
) {

    public static AdminDocumentResponse from(AdminDocumentSummary s) {
        return new AdminDocumentResponse(
                s.documentPublicId(), s.userPublicId(), s.userName(),
                s.analysisDocumentType(), s.language(),
                s.overallRiskLevel(), s.followUpAction(),
                s.analyzedAt()
        );
    }
}
