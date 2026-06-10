package com.gb.member.domain.member.entity;

/**
 * 마일스톤 기반 신뢰등급 (이슈 #193 — 레거시 "생활온도(temperature_grade)" 폐기·대체).
 *
 * <p>Phase 1은 Lv1~2 두 단계만 둔다:
 * <ul>
 *   <li>{@link #NEWCOMER} — Lv1. 가입 기본값.</li>
 *   <li>{@link #VERIFIED} — Lv2. 신분증 인증 승인(members.is_verified=true) 마일스톤 달성.</li>
 * </ul>
 *
 * <p>Phase 2+에서 상위 마일스톤(커뮤니티 활동·송금 실적 등) 등급이 추가될 예정이다.
 * 산정 규칙은 {@link com.gb.member.domain.member.service.TrustGradeService#recalculate} 단일 진입점에만 둔다.
 * JSON 직렬화 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(CLAUDE.md §5).
 */
public enum TrustGrade {
    NEWCOMER,
    VERIFIED
}
