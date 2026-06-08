package com.gb.wallet.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "거래 통계 응답")
public record TransactionStatsResponse(
        @Schema(description = "통화별 오늘 거래 합계(String 금액).")
        Map<String, String> todayTransactionsTotal,
        @Schema(description = "action별 거래 수.")
        Map<String, Long> byAction,
        @Schema(description = "status별 거래 수.")
        Map<String, Long> byStatus,
        @Schema(description = "송금 성공률 0.0~1.0. REMITTANCE(타행/해외) + INTERNAL_TRANSFER(앱내) 합산.")
        String remittanceSuccessRate,
        @Schema(description = "충전(CHARGE) 성공률 0.0~1.0.")
        String chargeSuccessRate,
        @Schema(description = "DAU = audit_log의 DISTINCT user_public_id.")
        long dailyActiveUsers,
        @Schema(description = "충전 실패 큐 카운트(현재 FAILED 상태의 CHARGE transactions).")
        long failedChargeQueueCount
) {
}
