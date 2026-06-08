package com.gb.admin.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 대시보드 요약 응답.
 *
 * <p>금액 필드는 conventions §0에 따라 {@code String} 십진수로 직렬화한다(BigDecimal → string).
 * Phase 1은 mock 데이터를 반환하고, 다음 스프린트에서 cross-service client로 실 집계로 교체한다.
 */
@Schema(description = "대시보드 요약")
public record DashboardSummaryResponse(
        @Schema(description = "오늘 거래 합계(통화별 String 금액).",
                example = "{\"KRW\":\"82400000.0000\",\"USD\":\"12500.0000\",\"VND\":\"1234567000.0000\"}")
        Map<String, String> todayTransactionsTotal,

        @Schema(description = "DAU(Daily Active Users).", example = "12430")
        long dailyActiveUsers,

        @Schema(description = "오늘 분석된 문서 수.", example = "348")
        long todayDocumentsAnalyzed,

        @Schema(description = "대기열(KYC/신고/충전 실패/AI 분석 실패) 수.",
                example = "{\"kyc_pending\":25,\"community_reports\":17,\"charge_failed\":3,\"analysis_failed\":2}")
        Map<String, Long> queues
) {
}
