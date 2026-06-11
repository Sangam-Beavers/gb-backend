package com.gb.member.domain.milestone.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 마일스톤 현황 응답 (Phase 2 — BE-6, GET /api/v1/members/me/trust-milestones).
 *
 * <p>{@code milestones}는 <b>전체 카탈로그(미달성 포함)</b>를 내려보낸다 — 프론트 등급 바텀시트가
 * "다음에 뭘 하면 되는지"(진척도 + CTA)를 그릴 수 있게 하기 위함(이슈 BE-6 §Etc).
 * Phase 3 카탈로그 7종:
 * ID_VERIFIED(합성) / BANK_ACCOUNT_CONNECTED / FIRST_TRANSACTION_COMPLETED (순차 체인 Lv2→Lv4) +
 * DOCUMENT_ANALYZED / COMMUNITY_ACTIVE / TRANSACTION_FIVE_COMPLETED / ACCOUNT_NINETY_DAYS(합성) (GOLD 보너스 4종).
 * 카탈로그 순서: 체인 3개 → 보너스 4개.
 *
 * <p>JSON은 전역 SNAKE_CASE 설정으로 변환된다(trust_grade/milestone_type/achieved_at).
 * {@code Boolean achieved}로 둔 건 getter가 {@code getAchieved()}가 되어 필드명이 {@code achieved}
 * 그대로 떨어지게 하기 위함(ProfileResponse의 isVerified 패턴과 동일 사유의 역방향 — primitive
 * boolean이어도 결과는 같지만 null-안전 일관성을 위해 래퍼 사용).
 */
@Getter
public class TrustMilestonesResponse {

    @Schema(description = "현재 신뢰등급(이슈 #193 — Phase 3 GOLD 추가)",
            allowableValues = {"NEWCOMER", "VERIFIED", "CONNECTED", "TRUSTED", "GOLD"}, example = "CONNECTED")
    private final String trustGrade;

    @Schema(description = "마일스톤 카탈로그 전체(미달성 포함, 등급 체인 순서 고정)")
    private final List<MilestoneItem> milestones;

    @Builder
    private TrustMilestonesResponse(String trustGrade, List<MilestoneItem> milestones) {
        this.trustGrade = trustGrade;
        this.milestones = milestones;
    }

    public static TrustMilestonesResponse of(String trustGrade, List<MilestoneItem> milestones) {
        return TrustMilestonesResponse.builder()
                .trustGrade(trustGrade)
                .milestones(milestones)
                .build();
    }

    /** 카탈로그 항목 1건 — { milestone_type, achieved, achieved_at(null 가능) }. */
    @Getter
    public static class MilestoneItem {

        @Schema(description = "마일스톤 종류. ID_VERIFIED·ACCOUNT_NINETY_DAYS=합성값, 나머지는 member_milestones 기록",
                allowableValues = {"ID_VERIFIED", "BANK_ACCOUNT_CONNECTED", "FIRST_TRANSACTION_COMPLETED",
                        "DOCUMENT_ANALYZED", "COMMUNITY_ACTIVE", "TRANSACTION_FIVE_COMPLETED", "ACCOUNT_NINETY_DAYS"},
                example = "BANK_ACCOUNT_CONNECTED")
        private final String milestoneType;

        @Schema(description = "달성 여부", example = "true")
        private final Boolean achieved;

        @Schema(description = "달성 시각(ISO 8601, UTC Z). 미달성이거나 시각을 알 수 없으면 null",
                example = "2026-06-10T05:21:08Z", nullable = true)
        private final String achievedAt;

        @Builder
        private MilestoneItem(String milestoneType, Boolean achieved, String achievedAt) {
            this.milestoneType = milestoneType;
            this.achieved = achieved;
            this.achievedAt = achievedAt;
        }

        public static MilestoneItem of(String milestoneType, boolean achieved, LocalDateTime achievedAt) {
            return MilestoneItem.builder()
                    .milestoneType(milestoneType)
                    .achieved(achieved)
                    // 달성인데 시각 미상(ID_VERIFIED 레코드 부재 등)은 null 허용 — 프론트는 achieved만으로 체크 표시.
                    .achievedAt(achieved ? toUtcZ(achievedAt) : null)
                    .build();
        }
    }

    /** LocalDateTime → ISO-8601 UTC 'Z' 문자열(초 단위 절삭). ProfileResponse와 동일 규칙. */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
