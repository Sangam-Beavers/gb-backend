package com.gb.wallet.domain.security.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 전자지갑 보안 점검 요약 응답 (GET /api/v1/wallets/me/security-summary).
 *
 * <p>최근 거래를 규칙 기반으로 점검한 결과를 담는다. 영속 데이터(이상거래 플래그 등)를 새로 만들지 않고
 * 기존 {@code transactions} 를 읽어 계산만 한다(읽기 전용). 홈 "이상거래 탐지" 카드 + 보안 점검 상세 화면용.
 *
 * <p>JSON 필드는 전역 설정(SNAKE_CASE)으로 변환된다 — DTO 필드는 camelCase 로 둔다.
 */
@Getter
public class SecuritySummaryResponse {

    @Schema(description = "종합 보안 상태", example = "SAFE", allowableValues = {"SAFE", "WARNING"})
    private final String status;

    @Schema(description = "점검한 거래 수(최근 30일, 최대 100건)", example = "23")
    private final int checkedCount;

    @Schema(description = "주의로 표시된(플래그된) 거래 수", example = "0")
    private final int suspiciousCount;

    @Schema(description = "점검 기준 시각(ISO 8601, UTC Z)", example = "2026-06-15T04:15:30Z")
    private final String checkedAt;

    @Schema(description = "점검 항목별 결과")
    private final List<CheckItem> checks;

    @Schema(description = "주의로 표시된 거래 목록(없으면 빈 배열)")
    private final List<FlaggedTransaction> flaggedTransactions;

    @Builder
    private SecuritySummaryResponse(String status, int checkedCount, int suspiciousCount,
            String checkedAt, List<CheckItem> checks, List<FlaggedTransaction> flaggedTransactions) {
        this.status = status;
        this.checkedCount = checkedCount;
        this.suspiciousCount = suspiciousCount;
        this.checkedAt = checkedAt;
        this.checks = checks;
        this.flaggedTransactions = flaggedTransactions;
    }

    /** 점검 대상이 없을 때(신규 사용자/지갑 없음/거래 없음)의 안전 응답. */
    public static SecuritySummaryResponse safeEmpty(Instant checkedAt, List<CheckItem> checks) {
        return SecuritySummaryResponse.builder()
                .status("SAFE")
                .checkedCount(0)
                .suspiciousCount(0)
                .checkedAt(toUtcZ(checkedAt))
                .checks(checks)
                .flaggedTransactions(List.of())
                .build();
    }

    public static SecuritySummaryResponse of(boolean anyWarning, int checkedCount,
            List<CheckItem> checks, List<FlaggedTransaction> flagged, Instant checkedAt) {
        return SecuritySummaryResponse.builder()
                .status(anyWarning ? "WARNING" : "SAFE")
                .checkedCount(checkedCount)
                .suspiciousCount(flagged.size())
                .checkedAt(toUtcZ(checkedAt))
                .checks(checks)
                .flaggedTransactions(flagged)
                .build();
    }

    static String toUtcZ(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    /** 점검 항목 1건(예: 심야 거래, 단시간 다발 등). */
    @Getter
    public static class CheckItem {

        @Schema(description = "항목 코드", example = "NIGHT_TRANSACTION",
                allowableValues = {"LARGE_AMOUNT", "RAPID_SUCCESSION", "NIGHT_TRANSACTION", "FAILED_ATTEMPTS"})
        private final String code;

        @Schema(description = "항목 표시명", example = "심야 시간대 거래")
        private final String label;

        @Schema(description = "항목 상태", example = "SAFE", allowableValues = {"SAFE", "WARNING"})
        private final String status;

        @Schema(description = "항목 설명/사유", example = "정상 범위입니다.")
        private final String detail;

        @Builder
        private CheckItem(String code, String label, String status, String detail) {
            this.code = code;
            this.label = label;
            this.status = status;
            this.detail = detail;
        }

        public static CheckItem of(String code, String label, boolean warning, String detail) {
            return CheckItem.builder()
                    .code(code)
                    .label(label)
                    .status(warning ? "WARNING" : "SAFE")
                    .detail(detail)
                    .build();
        }
    }

    /** 주의로 표시된 거래 1건. */
    @Getter
    public static class FlaggedTransaction {

        @Schema(description = "거래 식별자(UUID)", example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
        private final String publicId;

        @Schema(description = "거래 유형", example = "REMITTANCE",
                allowableValues = {"CHARGE", "INTERNAL_TRANSFER", "REMITTANCE", "EXCHANGE"})
        private final String type;

        @Schema(description = "금액(소수점 4자리 string)", example = "1500000.0000", type = "string")
        private final String amount;

        @Schema(description = "통화 코드", example = "KRW")
        private final String currencyCode;

        @Schema(description = "거래 시각(ISO 8601, UTC Z)", example = "2026-06-15T18:42:10Z")
        private final String createdAt;

        @Schema(description = "주의로 표시된 사유", example = "심야 시간대, 평소보다 큰 금액")
        private final String reason;

        @Builder
        private FlaggedTransaction(String publicId, String type, String amount,
                String currencyCode, String createdAt, String reason) {
            this.publicId = publicId;
            this.type = type;
            this.amount = amount;
            this.currencyCode = currencyCode;
            this.createdAt = createdAt;
            this.reason = reason;
        }
    }
}
