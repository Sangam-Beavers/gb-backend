package com.gb.admin.global.client;

import java.math.BigDecimal;
import java.util.Map;

public record AdminWalletStats(
        Map<String, BigDecimal> todayTransactionsTotal,
        Map<String, Long> byAction,
        Map<String, Long> byStatus,
        String remittanceSuccessRate,
        String chargeSuccessRate,
        long dailyActiveUsers,
        long failedChargeQueueCount
) {
}
