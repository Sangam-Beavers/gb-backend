package com.gb.admin.global.client;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 발표용 fixture — Mockup 이미지의 거래 3건과 일관성 있게 둔다.
 */
@Component
@Profile("mock-clients")
public class MockWalletAdminClient implements WalletAdminClient {

    private static final List<AdminTransactionSummary> FIXTURES = List.of(
            new AdminTransactionSummary(
                    "aaaaaaaa-0001-0000-0000-000000000001",
                    "11111111-1111-1111-1111-111111111111", "Nguyen Thi Linh",
                    "INTERNAL_TRANSFER",
                    new BigDecimal("1200000.0000"), "VND",
                    "COMPLETED", "LOW",
                    LocalDateTime.of(2026, 6, 7, 10, 12, 0)),
            new AdminTransactionSummary(
                    "aaaaaaaa-0001-0000-0000-000000000002",
                    "33333333-3333-3333-3333-333333333333", "Tran Van Minh",
                    "REMITTANCE",
                    new BigDecimal("800000.0000"), "KRW",
                    "PENDING", "MEDIUM",
                    LocalDateTime.of(2026, 6, 7, 11, 45, 0)),
            new AdminTransactionSummary(
                    "aaaaaaaa-0001-0000-0000-000000000003",
                    "44444444-4444-4444-4444-444444444444", "Tara Park",
                    "EXCHANGE",
                    new BigDecimal("120.0000"), "USD",
                    "COMPLETED", "LOW",
                    LocalDateTime.of(2026, 6, 7, 13, 22, 0))
    );

    @Override
    public Page<AdminTransactionSummary> searchTransactions(TransactionFilter filter, int page, int size) {
        List<AdminTransactionSummary> filtered = new ArrayList<>(FIXTURES);
        if (filter != null) {
            if (filter.type() != null && !filter.type().isBlank()) {
                filtered.removeIf(t -> !t.type().equalsIgnoreCase(filter.type()));
            }
            if (filter.status() != null && !filter.status().isBlank()) {
                filtered.removeIf(t -> !t.status().equalsIgnoreCase(filter.status()));
            }
            if (filter.risk() != null && !filter.risk().isBlank()) {
                filtered.removeIf(t -> !t.riskLevel().equalsIgnoreCase(filter.risk()));
            }
            if (filter.from() != null) {
                filtered.removeIf(t -> t.executedAt().isBefore(filter.from()));
            }
            if (filter.to() != null) {
                filtered.removeIf(t -> t.executedAt().isAfter(filter.to()));
            }
        }
        return AdminPage.of(filtered, page, size);
    }

    @Override
    public Page<AdminTransactionSummary> searchTransactionsForUser(TransactionFilter filter,
                                                                   String userPublicId, int page, int size) {
        List<AdminTransactionSummary> filtered = new ArrayList<>(FIXTURES);
        if (userPublicId != null && !userPublicId.isBlank()) {
            filtered.removeIf(t -> !userPublicId.equals(t.userPublicId()));
        }
        return AdminPage.of(filtered, page, size);
    }

    @Override
    public AdminTransactionAuditTrail getAuditTrail(String transactionPublicId) {
        AdminTransactionSummary tx = FIXTURES.stream()
                .filter(t -> t.transactionPublicId().equals(transactionPublicId))
                .findFirst()
                .orElse(FIXTURES.get(0));
        return new AdminTransactionAuditTrail(tx, java.util.List.of(
                new AdminAuditLogEntry(
                        tx.transactionPublicId() + ":1",
                        tx.transactionPublicId(),
                        tx.userPublicId(),
                        "CHARGE",
                        tx.amount(),
                        tx.currencyCode(),
                        new java.math.BigDecimal("100.0000"),
                        tx.amount(),
                        "COMPLETED",
                        null,
                        "127.0.0.1",
                        tx.executedAt())
        ));
    }

    @Override
    public Page<AdminAuditLogEntry> searchAuditLogs(AuditLogFilter filter, int page, int size) {
        // 발표용 fixture — 거래 fixtures 그대로를 audit log 형태로 변환.
        List<AdminAuditLogEntry> all = FIXTURES.stream()
                .map(t -> new AdminAuditLogEntry(
                        t.transactionPublicId() + ":1",
                        t.transactionPublicId(),
                        t.userPublicId(),
                        t.type(),
                        t.amount(),
                        t.currencyCode(),
                        new java.math.BigDecimal("0.0000"),
                        t.amount(),
                        t.status(),
                        null,
                        "127.0.0.1",
                        t.executedAt()))
                .toList();
        return AdminPage.of(all, page, size);
    }

    @Override
    public Page<AdminChargeAttempt> searchChargeAttempts(String status, String userPublicId, int page, int size) {
        List<AdminChargeAttempt> all = java.util.List.of(
                new AdminChargeAttempt(
                        "charge-attempt-001",
                        "11111111-1111-1111-1111-111111111111",
                        new java.math.BigDecimal("500000.0000"),
                        "KRW",
                        status == null || status.isBlank() ? "FAILED" : status,
                        "은행 응답 timeout",
                        1L,
                        java.time.LocalDateTime.of(2026, 6, 7, 10, 0, 0))
        );
        return AdminPage.of(all, page, size);
    }

    @Override
    public AdminWalletStats stats() {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("KRW", new BigDecimal("82400000.0000"));
        totals.put("USD", new BigDecimal("12500.0000"));
        totals.put("VND", new BigDecimal("1234567000.0000"));
        Map<String, Long> byAction = new LinkedHashMap<>();
        byAction.put("CHARGE", 120L);
        byAction.put("INTERNAL_TRANSFER", 45L);
        byAction.put("REMITTANCE", 88L);
        byAction.put("EXCHANGE", 23L);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("COMPLETED", 270L);
        byStatus.put("FAILED", 3L);
        byStatus.put("PENDING", 3L);
        return new AdminWalletStats(totals, byAction, byStatus, "0.9971", "0.9950", 12430L, 3L);
    }

    @Override
    public AdminRevenueStats revenue() {
        // 발표용 fixture — 누적 환전+송금 수수료 수익 + 통화별/월별 추이.
        List<AdminRevenueStats.CurrencyFee> byCurrency = List.of(
                new AdminRevenueStats.CurrencyFee("USD", "7650000.0000", "4120000.0000"),
                new AdminRevenueStats.CurrencyFee("VND", "5210000.0000", "3080000.0000"),
                new AdminRevenueStats.CurrencyFee("CNY", "3380000.0000", "1640000.0000"),
                new AdminRevenueStats.CurrencyFee("PHP", "1290000.0000", "560000.0000"),
                new AdminRevenueStats.CurrencyFee("NPR", "900000.0000", "320000.0000"));
        List<AdminRevenueStats.MonthlyFee> monthlyTrend = List.of(
                new AdminRevenueStats.MonthlyFee("2026-01", "2100000.0000", "1200000.0000"),
                new AdminRevenueStats.MonthlyFee("2026-02", "2450000.0000", "1350000.0000"),
                new AdminRevenueStats.MonthlyFee("2026-03", "2780000.0000", "1510000.0000"),
                new AdminRevenueStats.MonthlyFee("2026-04", "3020000.0000", "1640000.0000"),
                new AdminRevenueStats.MonthlyFee("2026-05", "3260000.0000", "1760000.0000"),
                new AdminRevenueStats.MonthlyFee("2026-06", "3210000.0000", "1840000.0000"));
        return new AdminRevenueStats(
                "KRW",
                "18430000.0000",
                "9720000.0000",
                "28150000.0000",
                "3210000.0000",
                "1840000.0000",
                byCurrency,
                monthlyTrend);
    }
}
