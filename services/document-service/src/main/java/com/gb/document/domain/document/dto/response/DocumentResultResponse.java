package com.gb.document.domain.document.dto.response;

import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.RiskItem;
import com.gb.document.domain.document.entity.WageSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 결과 상세 응답(GET /api/v1/documents/{id}/result). api-spec.md §3 (v1.1) SSOT.
 *
 * <p>금액(BigDecimal)은 conventions §0에 따라 string으로 직렬화하고, 시각은 ISO 8601 UTC Z 문자열.
 * ocr_confidence는 표시 전용 수치라 number로 그대로 노출(예외 허용).
 */
@Getter
public class DocumentResultResponse {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private final String documentPublicId;

    @Schema(allowableValues = {"LABOR_CONTRACT", "PAYSLIP", "EMPLOYMENT_CONTRACT"})
    private final String analysisDocumentType;

    @Schema(allowableValues = {"COMPLETED", "FAILED", "PARTIAL"})
    private final String processingStatus;

    @Schema(allowableValues = {"LOW", "MEDIUM", "HIGH"}, nullable = true)
    private final String overallRiskLevel;

    @Schema(description = "OCR 신뢰도. 표시 전용 number.", example = "0.92")
    private final BigDecimal ocrConfidence;

    private final WageSummaryDto wageSummary;
    private final List<RiskItemDto> riskItems;
    private final String translatedText;
    private final String translatedLang;
    private final String maskedFileUrl;
    private final String failedReason;
    private final String completedAt;
    private final String createdAt;
    private final String updatedAt;

    @Builder
    private DocumentResultResponse(String documentPublicId, String analysisDocumentType,
                                   String processingStatus, String overallRiskLevel,
                                   BigDecimal ocrConfidence, WageSummaryDto wageSummary,
                                   List<RiskItemDto> riskItems, String translatedText,
                                   String translatedLang, String maskedFileUrl, String failedReason,
                                   String completedAt, String createdAt, String updatedAt) {
        this.documentPublicId = documentPublicId;
        this.analysisDocumentType = analysisDocumentType;
        this.processingStatus = processingStatus;
        this.overallRiskLevel = overallRiskLevel;
        this.ocrConfidence = ocrConfidence;
        this.wageSummary = wageSummary;
        this.riskItems = riskItems;
        this.translatedText = translatedText;
        this.translatedLang = translatedLang;
        this.maskedFileUrl = maskedFileUrl;
        this.failedReason = failedReason;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static DocumentResultResponse from(DocumentResult result) {
        return DocumentResultResponse.builder()
                .documentPublicId(result.getSubmission().getPublicId())
                .analysisDocumentType(result.getAnalysisDocumentType().name())
                .processingStatus(result.getProcessingStatus().name())
                .overallRiskLevel(result.getOverallRiskLevel() != null
                        ? result.getOverallRiskLevel().name() : null)
                .ocrConfidence(result.getOcrConfidence())
                .wageSummary(WageSummaryDto.from(result.getWageSummary()))
                .riskItems(result.getRiskItems() == null ? List.of()
                        : result.getRiskItems().stream().map(RiskItemDto::from).toList())
                .translatedText(result.getTranslatedText())
                .translatedLang(result.getTranslatedLang())
                .maskedFileUrl(result.getMaskedFileUrl())
                .failedReason(result.getFailedReason())
                .completedAt(toUtcZ(result.getCompletedAt()))
                .createdAt(toUtcZ(result.getCreatedAt()))
                .updatedAt(toUtcZ(result.getUpdatedAt()))
                .build();
    }

    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Getter
    public static class WageSummaryDto {
        private final String currencyCode;
        private final String monthlyWage;
        private final String hourlyWage;
        private final List<DeductionDto> deductions;

        @Builder
        private WageSummaryDto(String currencyCode, String monthlyWage, String hourlyWage,
                               List<DeductionDto> deductions) {
            this.currencyCode = currencyCode;
            this.monthlyWage = monthlyWage;
            this.hourlyWage = hourlyWage;
            this.deductions = deductions;
        }

        public static WageSummaryDto from(WageSummary summary) {
            if (summary == null) return null;
            return WageSummaryDto.builder()
                    .currencyCode(summary.currencyCode())
                    .monthlyWage(toPlain(summary.monthlyWage()))
                    .hourlyWage(toPlain(summary.hourlyWage()))
                    .deductions(summary.deductions() == null ? List.of()
                            : summary.deductions().stream().map(DeductionDto::from).toList())
                    .build();
        }
    }

    @Getter
    public static class DeductionDto {
        private final String name;
        private final String amount;

        @Builder
        private DeductionDto(String name, String amount) {
            this.name = name;
            this.amount = amount;
        }

        public static DeductionDto from(WageSummary.Deduction deduction) {
            return DeductionDto.builder()
                    .name(deduction.name())
                    .amount(toPlain(deduction.amount()))
                    .build();
        }
    }

    @Getter
    public static class RiskItemDto {
        private final String riskLevel;
        private final String clause;
        private final String description;

        @Builder
        private RiskItemDto(String riskLevel, String clause, String description) {
            this.riskLevel = riskLevel;
            this.clause = clause;
            this.description = description;
        }

        public static RiskItemDto from(RiskItem item) {
            return RiskItemDto.builder()
                    .riskLevel(item.riskLevel() != null ? item.riskLevel().name() : null)
                    .clause(item.clause())
                    .description(item.description())
                    .build();
        }
    }

    private static String toPlain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
