package com.gb.admin.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "대시보드 알림 목록")
public record DashboardAlertsResponse(
        @Schema(description = "알림 목록.")
        List<Alert> alerts
) {

    @Schema(description = "알림 단건")
    public record Alert(
            @Schema(description = "알림 종류(SCREAMING_SNAKE_CASE).",
                    example = "SUSPICIOUS_TRANSACTION",
                    allowableValues = {"SUSPICIOUS_TRANSACTION", "KYC_PENDING", "COMMUNITY_REPORT",
                            "CHARGE_FAILED", "ANALYSIS_FAILED"})
            String type,

            @Schema(description = "표시 라벨.", example = "이상거래")
            String label,

            @Schema(description = "메시지.", example = "고액 송금 3건 탐지")
            String message,

            @Schema(description = "처리 상태.",
                    example = "REVIEW_NEEDED",
                    allowableValues = {"REVIEW_NEEDED", "PENDING", "ACTION_NEEDED", "RESOLVED"})
            String status,

            @Schema(description = "발생 시각(UTC).", example = "2026-06-08T10:21:00Z")
            LocalDateTime createdAt
    ) {
    }
}
