package com.gb.admin.global.client;

import java.util.List;

public record AdminTransactionAuditTrail(
        AdminTransactionSummary transaction,
        List<AdminAuditLogEntry> logs
) {
}
