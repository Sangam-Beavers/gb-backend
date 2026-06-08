package com.gb.wallet.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "거래 1건 + 그에 매달린 audit_log 시간순")
public record TransactionAuditTrailResponse(
        AdminTransactionView transaction,
        List<TransactionAuditLogView> logs
) {
}
