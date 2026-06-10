package com.gb.member.domain.milestone.dto.event;

import java.time.Instant;

/**
 * 토픽 {@code wallet.milestone-achieved.v1} 수신 페이로드 (Phase 2 — BE-4, 스파이크 결정 2 스키마).
 *
 * <p>발행자(wallet-service)와 클래스를 공유하지 않는 <b>컨슈머측 미러 DTO</b>다(MSA 경계 — 계약은
 * 스파이크 문서의 JSON 스키마). 역직렬화는 {@code KafkaConsumerConfig}가 Spring Boot 전역 ObjectMapper
 * (snake_case 네이밍 + JavaTimeModule + 미지 필드 무시)를 {@code JsonDeserializer}에 명시 주입해 처리한다
 * — 발행측이 같은 토픽 버전에서 필드를 추가해도 깨지지 않는다(전방 호환, 결정 1).
 *
 * <p>{@code milestoneType}은 enum이 아닌 String으로 받는다 — 미지의 마일스톤 값(추후 확장)이
 * 역직렬화 단계에서 poison pill이 되지 않게 하고, enum 변환·검증은 Service에서 수행한다(CLAUDE §6 사상).
 *
 * @param eventId       발행 이벤트 식별자(UUID) — 추적/로깅용
 * @param eventType     "MILESTONE_ACHIEVED" 고정
 * @param milestoneType 마일스톤 종류(SCREAMING_SNAKE_CASE 문자열)
 * @param userPublicId  달성 회원 public_id(UUID)
 * @param occurredAt    달성 시각(ISO 8601 UTC Z)
 * @param sourceService 발행 서비스명("wallet-service")
 * @param schemaVersion 스키마 메이저 버전(현재 1)
 */
public record MilestoneAchievedEvent(
        String eventId,
        String eventType,
        String milestoneType,
        String userPublicId,
        Instant occurredAt,
        String sourceService,
        Integer schemaVersion) {
}
