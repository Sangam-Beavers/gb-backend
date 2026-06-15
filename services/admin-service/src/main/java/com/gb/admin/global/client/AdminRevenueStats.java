package com.gb.admin.global.client;

import java.util.List;

/**
 * wallet-service 수익(환전/송금 수수료) 통계 — admin-service 가 BFF 로 relay 한다.
 * 금액은 wallet 응답 그대로 String(소수 4자리)으로 유지한다.
 */
public record AdminRevenueStats(
        String currencyCode,
        String totalExchangeFee,
        String totalRemittanceFee,
        String totalFeeRevenue,
        String thisMonthExchangeFee,
        String thisMonthRemittanceFee,
        List<CurrencyFee> byCurrency,
        List<MonthlyFee> monthlyTrend
) {

    public record CurrencyFee(String currencyCode, String exchangeFee, String remittanceFee) {
    }

    public record MonthlyFee(String month, String exchangeFee, String remittanceFee) {
    }
}
