package com.gb.admin.domain.financialAudit.dto.response;

import com.gb.admin.global.client.AdminAuditLogEntry;
import com.gb.admin.global.client.AdminMemberMini;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "금융 감사 로그 단건(회원 표시 정보 enrich 포함)")
public record FinancialAuditLogResponse(
        String auditLogPublicId,
        String transactionPublicId,
        String userPublicId,
        String userEmail,
        String userNickname,
        String action,
        @Schema(description = "한글 라벨 — 발표용 액션 매핑.")
        String actionLabel,
        String amount,
        String currencyCode,
        String beforeBalance,
        String afterBalance,
        String status,
        String statusLabel,
        String reason,
        String ipAddress,
        LocalDateTime createdAt
) {

    public static FinancialAuditLogResponse from(AdminAuditLogEntry e, AdminMemberMini member) {
        return new FinancialAuditLogResponse(
                e.auditLogPublicId(),
                e.transactionPublicId(),
                e.userPublicId(),
                member != null ? member.email() : "Unknown",
                member != null ? member.nickname() : "Unknown",
                e.action(),
                actionLabel(e.action()),
                e.amount() == null ? null : e.amount().toPlainString(),
                e.currencyCode(),
                e.beforeBalance() == null ? null : e.beforeBalance().toPlainString(),
                e.afterBalance() == null ? null : e.afterBalance().toPlainString(),
                e.status(),
                statusLabel(e.status()),
                e.reason(),
                e.ipAddress(),
                e.createdAt()
        );
    }

    private static String actionLabel(String action) {
        if (action == null) return "-";
        return switch (action.toUpperCase()) {
            case "CHARGE" -> "충전";
            case "INTERNAL_TRANSFER", "TRANSFER" -> "내부 송금";
            case "REMITTANCE" -> "해외 송금";
            case "EXCHANGE" -> "환전";
            case "PAYOUT" -> "현금화";
            case "CANCEL" -> "취소";
            default -> action;
        };
    }

    private static String statusLabel(String status) {
        if (status == null) return "-";
        return switch (status.toUpperCase()) {
            case "COMPLETED" -> "완료";
            case "FAILED" -> "실패";
            case "PENDING" -> "대기";
            case "PROCESSING" -> "처리중";
            case "CANCELLED" -> "취소됨";
            default -> status;
        };
    }
}
