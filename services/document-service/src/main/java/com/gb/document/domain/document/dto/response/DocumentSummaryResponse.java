package com.gb.document.domain.document.dto.response;

import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 목록 응답 한 항목(GET /api/v1/documents).
 *
 * <p>overall_risk_level은 결과 테이블에서 join한 값. ANALYZING/FAILED 또는 결과 미생성 상태에서는 null.
 */
@Getter
public class DocumentSummaryResponse {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private final String publicId;

    private final String analysisDocumentType;
    private final String fileName;
    private final String status;
    private final String overallRiskLevel;
    private final String createdAt;

    @Builder
    private DocumentSummaryResponse(String publicId, String analysisDocumentType, String fileName,
                                    String status, String overallRiskLevel, String createdAt) {
        this.publicId = publicId;
        this.analysisDocumentType = analysisDocumentType;
        this.fileName = fileName;
        this.status = status;
        this.overallRiskLevel = overallRiskLevel;
        this.createdAt = createdAt;
    }

    public static DocumentSummaryResponse from(Document document, DocumentResult result) {
        String risk = (result != null && result.getOverallRiskLevel() != null)
                ? result.getOverallRiskLevel().name() : null;
        return DocumentSummaryResponse.builder()
                .publicId(document.getPublicId())
                .analysisDocumentType(document.getAnalysisDocumentType().name())
                .fileName(document.getFileName())
                .status(document.getStatus().name())
                .overallRiskLevel(risk)
                .createdAt(toUtcZ(document.getCreatedAt()))
                .build();
    }

    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
