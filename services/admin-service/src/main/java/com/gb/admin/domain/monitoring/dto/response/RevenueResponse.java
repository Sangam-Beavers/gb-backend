package com.gb.admin.domain.monitoring.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 수익 응답 — "우리 앱이 환전/송금 수수료로 번 돈".
 *
 * <p>wallet-service 의 COMPLETED 거래 fee 집계를 BFF 로 relay 한다. 금액은 CLAUDE §5 규약대로 String.
 * 문서분석 구독 매출은 별도 도메인(미구현)이라 본 응답에 포함하지 않는다 — 프론트가 mock 으로 채운다.
 */
@Schema(description = "수익(환전/송금 수수료) 응답")
public record RevenueResponse(
        @Schema(description = "수수료 수익 집계")
        FeeRevenue feeRevenue,
        @Schema(description = "월별 수수료 수익 추이(최근 6개월)")
        List<MonthlyFee> monthlyTrend
) {

    @Schema(description = "수수료 수익 집계(누적·이번 달·통화별)")
    public record FeeRevenue(
            @Schema(description = "기준 통화", example = "KRW")
            String currencyCode,
            @Schema(description = "누적 환전 수수료 수익(String)", example = "18430000.0000")
            String totalExchangeFee,
            @Schema(description = "누적 송금 수수료 수익(String)", example = "9720000.0000")
            String totalRemittanceFee,
            @Schema(description = "누적 수수료 수익 총합(String)", example = "28150000.0000")
            String totalFeeRevenue,
            @Schema(description = "이번 달 환전 수수료 수익(String)", example = "3210000.0000")
            String thisMonthExchangeFee,
            @Schema(description = "이번 달 송금 수수료 수익(String)", example = "1840000.0000")
            String thisMonthRemittanceFee,
            @Schema(description = "통화별 수수료 수익 내역")
            List<CurrencyFee> byCurrency
    ) {
    }

    @Schema(description = "통화별 환전/송금 수수료")
    public record CurrencyFee(
            @Schema(description = "통화 코드", example = "USD")
            String currencyCode,
            @Schema(description = "환전 수수료 수익(String)")
            String exchangeFee,
            @Schema(description = "송금 수수료 수익(String)")
            String remittanceFee
    ) {
    }

    @Schema(description = "월별 수수료 수익")
    public record MonthlyFee(
            @Schema(description = "연-월", example = "2026-06")
            String month,
            @Schema(description = "환전 수수료 수익(String)")
            String exchangeFee,
            @Schema(description = "송금 수수료 수익(String)")
            String remittanceFee
    ) {
    }
}
