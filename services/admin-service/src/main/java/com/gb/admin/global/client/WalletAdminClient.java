package com.gb.admin.global.client;

import org.springframework.data.domain.Page;

public interface WalletAdminClient {

    Page<AdminTransactionSummary> searchTransactions(TransactionFilter filter, int page, int size);

    Page<AdminTransactionSummary> searchTransactionsForUser(TransactionFilter filter, String userPublicId,
                                                           int page, int size);

    AdminTransactionAuditTrail getAuditTrail(String transactionPublicId);

    Page<AdminAuditLogEntry> searchAuditLogs(AuditLogFilter filter, int page, int size);

    Page<AdminChargeAttempt> searchChargeAttempts(String status, String userPublicId, int page, int size);

    AdminWalletStats stats();

    AdminRevenueStats revenue();
}
