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
 * <p>GOLD(보너스 마일스톤 2개 — 서류 분석·커뮤니티 활동)는 Phase 3에서 추가 예정.
 * 인증 취소 시 마일스톤 기록은 보존하되 등급만 NEWCOMER로 강등되고, 재인증하면 기록 덕에 즉시
 * CONNECTED+로 복귀한다(순차 체인 — 스파이크 "등급 재계산 규칙 확장").
 * 산정 규칙은 {@link com.gb.member.domain.member.service.TrustGradeService#recalculate} 단일 진입점에만 둔다.
 * JSON 직렬화 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(CLAUDE.md §5).
 */
public enum TrustGrade {
    NEWCOMER,
    VERIFIED,
    CONNECTED,
    TRUSTED
}
