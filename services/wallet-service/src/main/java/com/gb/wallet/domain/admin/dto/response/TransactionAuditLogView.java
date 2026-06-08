package com.gb.wallet.domain.admin.dto.response;

import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "금융 감사 로그 단건")
public record TransactionAuditLogView(
        @Schema(description = "transaction_audit_log의 안정 식별자. id 노출 금지로 transactionPublicId+id 합성 hex 사용.")
        String auditLogPublicId,
        String transactionPublicId,
        String userPublicId,
        String action,
        String amount,
        String currencyCode,
        String beforeBalance,
        String afterBalance,
        String status,
        String reason,
        String ipAddress,
        LocalDateTime createdAt
) {

    public static TransactionAuditLogView from(TransactionAuditLog l) {
        // audit_log엔 public_id 컬럼이 없다(database.md). 외부 노출용으로
        // transaction.public_id + ":" + log.id 형태의 합성 id를 만든다(내부 BIGINT 단독 노출 금지 — CLAUDE §5).
        String publicId = l.getTransaction().getPublicId() + ":" + l.getId();
        return new TransactionAuditLogView(
                publicId,
                l.getTransaction().getPublicId(),
                l.getUserPublicId(),
                l.getAction(),
                l.getAmount() == null ? null : l.getAmount().toPlainString(),
                l.getCurrencyCode() == null ? null : l.getCurrencyCode().name(),
                l.getBeforeBalance() == null ? null : l.getBeforeBalance().toPlainString(),
                l.getAfterBalance() == null ? null : l.getAfterBalance().toPlainString(),
                l.getStatus() == null ? null : l.getStatus().name(),
                l.getReason(),
                l.getIpAddress(),
                l.getCreatedAt()
        );
    }
}
