package com.gb.wallet.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 수익(매출) 통계 응답 — 앱이 환전/송금 수수료로 번 돈.
 *
 * <p>COMPLETED 거래의 {@code transactions.fee} 합계를 유형(EXCHANGE/REMITTANCE)·기간별로 집계한다.
 * 금액은 CLAUDE §5 규약대로 String 으로 전송한다. 기준 통화는 {@code currencyCode}(KRW) —
 * 외국인 근로자 환전/송금의 출금 통화가 KRW 라 수수료도 KRW 기준이며, 통화별 내역은 {@code byCurrency}로 분해한다.
 */
@Schema(description = "수익(환전/송금 수수료) 통계")
public record RevenueStatsResponse(
        @Schema(description = "기준 통화", example = "KRW")
        String currencyCode,
        @Schema(description = "누적 환전 수수료 수익(String)", example = "18430000.0000")
        String totalExchangeFee,
        @Schema(description = "누적 송금 수수료 수익(String)", example = "9720000.0000")
        String totalRemittanceFee,
        @Schema(description = "누적 수수료 수익 총합(환전+송금, String)", example = "28150000.0000")
        String totalFeeRevenue,
        @Schema(description = "이번 달 환전 수수료 수익(String)", example = "3210000.0000")
        String thisMonthExchangeFee,
        @Schema(description = "이번 달 송금 수수료 수익(String)", example = "1840000.0000")
        String thisMonthRemittanceFee,
        @Schema(description = "통화별 수수료 수익 내역")
        List<CurrencyFee> byCurrency,
        @Schema(description = "월별 수수료 수익 추이(최근 6개월)")
        List<MonthlyFee> monthlyTrend
) {

    @Schema(description = "통화별 환전/송금 수수료")
    public record CurrencyFee(
            @Schema(description = "통화 코드", example = "USD")
            String currencyCode,
            @Schema(description = "환전 수수료 수익(String)", example = "7650000.0000")
            String exchangeFee,
            @Schema(description = "송금 수수료 수익(String)", example = "4120000.0000")
            String remittanceFee
    ) {
    }

    @Schema(description = "월별 수수료 수익")
    public record MonthlyFee(
            @Schema(description = "연-월", example = "2026-06")
            String month,
            @Schema(description = "환전 수수료 수익(String)", example = "3210000.0000")
            String exchangeFee,
            @Schema(description = "송금 수수료 수익(String)", example = "1840000.0000")
            String remittanceFee
    ) {
    }
}
