package com.gb.admin.domain.financialAudit.dto.response;

import com.gb.admin.global.client.AdminMemberMini;
import com.gb.admin.global.client.AdminTransactionAuditTrail;
import com.gb.admin.global.client.AdminTransactionSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "거래 1건 + 감사 로그 시간순(회원 표시 정보 enrich 포함)")
public record TransactionAuditTrailResponse(
        TransactionInfo transaction,
        List<FinancialAuditLogResponse> logs
) {

    public static TransactionAuditTrailResponse from(AdminTransactionAuditTrail trail, Map<String, AdminMemberMini> members) {
        AdminTransactionSummary t = trail.transaction();
        AdminMemberMini txMember = members.get(t.userPublicId());
        TransactionInfo txInfo = new TransactionInfo(
                t.transactionPublicId(),
                t.userPublicId(),
                txMember != null ? txMember.email() : "Unknown",
                txMember != null ? txMember.nickname() : "Unknown",
                t.type(),
                t.amount() == null ? null : t.amount().toPlainString(),
                t.currencyCode(),
                t.status(),
                t.riskLevel(),
                t.executedAt());
        List<FinancialAuditLogResponse> mapped = trail.logs().stream()
                .map(l -> FinancialAuditLogResponse.from(l, members.get(l.userPublicId())))
                .toList();
        return new TransactionAuditTrailResponse(txInfo, mapped);
    }

    @Schema(description = "거래 상세")
    public record TransactionInfo(
            String transactionPublicId,
            String userPublicId,
            String userEmail,
            String userNickname,
            String type,
            String amount,
            String currencyCode,
            String status,
            String riskLevel,
            LocalDateTime executedAt
    ) {
    }
}
