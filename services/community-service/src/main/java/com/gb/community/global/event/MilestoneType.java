package com.gb.community.global.event;

/**
 * community-service가 발행하는 신뢰등급 마일스톤 종류 (Phase 3 — BE-8).
 *
 * <p>JSON 와이어 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(컨벤션 §5). member-service의
 * Consumer 쪽 enum과 이름이 1:1로 일치해야 한다 — MSA 경계라 클래스는 공유하지 않고
 * <b>이름(문자열) 계약</b>만 공유한다(스키마 SSOT = Phase2 이벤트 컨벤션 스파이크 문서 + Phase3 §0).
 *
 * <p><b>BE-8 계약 정정:</b> 초기 이름 {@code COMMUNITY_DEBUT} → {@code COMMUNITY_ACTIVE}로 통일.
 * member-service 수신측이 {@code COMMUNITY_ACTIVE}를 기대하므로 와이어 계약 이름을 맞춘다.
 */
public enum MilestoneType {

    /**
     * 커뮤니티 데뷔 — 게시글 또는 댓글 작성 성공 (GOLD 보너스 "커뮤니티 데뷔").
     * "첫 글인지" 판단 없이 매번 발행 — 수신측(member)이 (user, milestone) UNIQUE로 자연 멱등 스킵.
     * 글/댓글을 삭제해도 마일스톤은 회수하지 않는다(영구 달성 정책).
     */
    COMMUNITY_ACTIVE
}
