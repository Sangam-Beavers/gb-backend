package com.gb.admin.domain.monitoring.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 비즈니스 분석 응답 — "어떤 사용자가 우리 서비스를 쓰는가 + 어디서 매출이 나는가".
 *
 * <p>인프라/헬스 모니터링과 분리된 별도 카테고리. member-service 인구통계 +
 * wallet-service 거래/사용 통계를 합쳐 한 번에 내려준다. 각 client는 fail-open 으로
 * 묶이므로 한쪽이 죽어도 나머지 값은 표시된다.
 */
@Schema(description = "비즈니스 분석(인구통계 + 매출/사용)")
public record BusinessAnalyticsResponse(
        @Schema(description = "사용자 인구통계 분포")
        Demographics demographics,
        @Schema(description = "매출/사용 지표")
        Revenue revenue
) {

    @Schema(description = "분포 버킷")
    public record Bucket(
            @Schema(description = "구분 키(enum 이름 또는 국적 ISO alpha-2)", example = "MALE")
            String key,
            @Schema(description = "건수", example = "7890")
            long count
    ) {
    }

    @Schema(description = "사용자 인구통계")
    public record Demographics(
            @Schema(description = "성별 분포")
            List<Bucket> genderDistribution,
            @Schema(description = "연령대 분포")
            List<Bucket> ageDistribution,
            @Schema(description = "국적 분포(많은 순)")
            List<Bucket> nationalityDistribution
    ) {
    }

    @Schema(description = "매출/사용 지표")
    public record Revenue(
            @Schema(description = "총 회원 수", example = "12430")
            long totalMembers,
            @Schema(description = "오늘 신규 가입 수", example = "38")
            long newMembersToday,
            @Schema(description = "일일 활성 사용자(DAU)", example = "2140")
            long dailyActiveUsers,
            @Schema(description = "오늘 거래 총액(통화별, 금액은 String)",
                    example = "{\"KRW\":\"153000000.0000\"}")
            Map<String, String> todayTransactionsTotal,
            @Schema(description = "거래 유형별 건수(CHARGE/TRANSFER/REMITTANCE/EXCHANGE 등) — 어떤 기능이 많이 쓰이는지")
            List<Bucket> transactionsByAction,
            @Schema(description = "환전 수수료율(매출원, String 십진수)", example = "0.005")
            String exchangeFeeRate
    ) {
    }
}
