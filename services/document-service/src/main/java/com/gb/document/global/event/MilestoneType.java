package com.gb.document.global.event;

/**
 * document-service가 발행하는 신뢰등급 마일스톤 종류 (Phase 3 — BE-7).
 *
 * <p>JSON 와이어 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(컨벤션 §5). member-service의
 * Consumer 쪽 enum과 이름이 1:1로 일치해야 한다 — MSA 경계라 클래스는 공유하지 않고
 * <b>이름(문자열) 계약</b>만 공유한다(스키마 SSOT = Phase2 이벤트 컨벤션 스파이크 문서 + Phase3 §0).
 */
public enum MilestoneType {

    /**
     * 서류 분석 결과 수신·저장 완료 (GOLD 보너스 "서류 전문가"). 분석 실패(FAILED) 건은 발행하지 않는다.
     * 같은 유저가 여러 서류를 분석해도 매번 발행 — 수신측(member)이 (user, milestone) UNIQUE로 자연 멱등 스킵.
     */
    DOCUMENT_ANALYZED
}
