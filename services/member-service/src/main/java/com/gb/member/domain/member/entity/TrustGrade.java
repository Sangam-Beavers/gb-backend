package com.gb.member.domain.member.entity;

/**
 * 마일스톤 기반 신뢰등급 (이슈 #193 — 레거시 "생활온도(temperature_grade)" 폐기·대체).
 *
 * <p>Phase 2 기준 Lv1~4 네 단계(순차 체인 — 상위 등급은 하위 조건을 모두 포함한다):
 * <ul>
 *   <li>{@link #NEWCOMER} — Lv1. 가입 기본값.</li>
 *   <li>{@link #VERIFIED} — Lv2. 신분증 인증 승인(members.is_verified=true).</li>
 *   <li>{@link #CONNECTED} — Lv3. VERIFIED && 계좌 인증·연결(BANK_ACCOUNT_CONNECTED 마일스톤).</li>
 *   <li>{@link #TRUSTED} — Lv4. CONNECTED && 첫 금융 거래 완료(FIRST_TRANSACTION_COMPLETED 마일스톤).</li>
 * </ul>
 *
 * <p>Phase 3 Lv5 — GOLD: TRUSTED + 4개 보너스 마일스톤 중 2개 이상 달성.
 * 보너스 4종: DOCUMENT_ANALYZED(서류 분석 1건) · COMMUNITY_ACTIVE(게시글·댓글 1건) ·
 * TRANSACTION_FIVE_COMPLETED(누적 거래 5건) · ACCOUNT_NINETY_DAYS(가입 후 90일 경과 — 합성값).
 * 인증 취소 시 마일스톤 기록은 보존하되 등급만 NEWCOMER로 강등되고, 재인증하면 기록 덕에 즉시
 * CONNECTED+로 복귀한다(순차 체인 — 스파이크 "등급 재계산 규칙 확장").
 * 산정 규칙은 {@link com.gb.member.domain.member.service.TrustGradeService#recalculate} 단일 진입점에만 둔다.
 * JSON 직렬화 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(CLAUDE.md §5).
 */
public enum TrustGrade {
    NEWCOMER,
    VERIFIED,
    CONNECTED,
    TRUSTED,

    /**
     * Lv5 — GOLD. TRUSTED + 4개 보너스 마일스톤 중 2개 이상 달성 시 부여(Phase 3).
     * 보너스 4종: DOCUMENT_ANALYZED / COMMUNITY_ACTIVE / TRANSACTION_FIVE_COMPLETED /
     * ACCOUNT_NINETY_DAYS(합성 — member.createdAt 기준 90일 경과).
     */
    GOLD
}
