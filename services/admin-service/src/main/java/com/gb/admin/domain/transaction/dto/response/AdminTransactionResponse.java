package com.gb.admin.domain.transaction.dto.response;

import com.gb.admin.global.client.AdminTransactionSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "관리자 거래 로그 단건 응답")
public record AdminTransactionResponse(
        @Schema(description = "거래 public_id.", example = "aaaaaaaa-0001-0000-0000-000000000001")
        String transactionPublicId,

        @Schema(description = "사용자 public_id.", example = "11111111-1111-1111-1111-111111111111")
        String userPublicId,

        @Schema(description = "사용자 표시 이름.", example = "Nguyen Thi Linh")
        String userName,

        @Schema(description = "거래 유형(SCREAMING_SNAKE_CASE).", example = "INTERNAL_TRANSFER",
                allowableValues = {"INTERNAL_TRANSFER", "REMITTANCE", "EXCHANGE", "CHARGE", "PAYOUT"})
        String type,

        @Schema(description = "금액(String 십진수).", example = "1200000.0000")
        String amount,

        @Schema(description = "통화 코드.", example = "VND")
        String currencyCode,

        @Schema(description = "거래 상태.", example = "COMPLETED",
                allowableValues = {"PENDING", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"})
        String status,

        @Schema(description = "리스크 레벨.", example = "LOW",
                allowableValues = {"LOW", "MEDIUM", "HIGH"})
        String riskLevel,

        @Schema(description = "거래 실행 시각(UTC).", example = "2026-06-07T10:12:00Z")
        LocalDateTime executedAt
) {

    public static AdminTransactionResponse from(AdminTransactionSummary s) {
        return new AdminTransactionResponse(
                s.transactionPublicId(),
                s.userPublicId(),
                s.userName(),
                s.type(),
                s.amount() == null ? null : s.amount().toPlainString(),
                s.currencyCode(),
                s.status(),
                s.riskLevel(),
                s.executedAt()
        );
    }
}
