package com.gb.document.global.event;

/**
 * 도메인 서비스 → {@link MilestoneEventPublisher} 사이의 <b>내부</b> Spring ApplicationEvent (Phase 3 — BE-7).
 *
 * <p>도메인 서비스(분석 결과 영속화 — SQS Consumer 경로)는 비즈니스 트랜잭션 안에서 이 가벼운 이벤트만
 * {@code ApplicationEventPublisher.publishEvent}로 발행한다. Kafka 페이로드 조립(event_id·occurred_at 등)과
 * 실제 전송은 커밋 후({@code AFTER_COMMIT}) {@link MilestoneEventPublisher}가 담당한다 —
 * 도메인 코드가 Kafka를 직접 알지 않게 하고, 커밋 전 발행(유령 이벤트)을 구조적으로 차단한다(스파이크 결정 4).
 *
 * @param userPublicId  마일스톤을 달성한 회원(=문서 소유자)의 public_id(UUID — MSA 경계 참조, CLAUDE §7)
 * @param milestoneType 달성한 마일스톤 종류
 */
public record MilestoneAchieved(String userPublicId, MilestoneType milestoneType) {
}
