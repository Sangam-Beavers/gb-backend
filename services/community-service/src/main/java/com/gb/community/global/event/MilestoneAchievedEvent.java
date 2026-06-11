package com.gb.community.global.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 토픽 {@code community.milestone-achieved.v1}의 Kafka 페이로드 (Phase 3 — 스파이크 결정 2 스키마 그대로).
 *
 * <pre>{@code
 * {
 *   "event_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
 *   "event_type": "MILESTONE_ACHIEVED",
 *   "milestone_type": "COMMUNITY_ACTIVE",
 *   "user_public_id": "9b2f...uuid",
 *   "occurred_at": "2026-06-10T05:21:08Z",
 *   "source_service": "community-service",
 *   "schema_version": 1
 * }
 * }</pre>
 *
 * <p>snake_case 변환은 DTO에 {@code @JsonProperty}를 붙이지 않고 <b>Spring Boot 전역 ObjectMapper</b>
 * (application.yaml {@code spring.jackson.property-naming-strategy: SNAKE_CASE})로 처리한다 —
 * {@link com.gb.community.global.config.KafkaProducerConfig}가 그 ObjectMapper를 {@code JsonSerializer}에
 * 명시 주입하므로 HTTP 응답과 같은 직렬화 규칙이 Kafka에도 적용된다(기본 JsonSerializer는 자체
 * ObjectMapper를 만들어 snake_case가 깨지므로 주의). {@code occurredAt}은 {@link Instant}라
 * ISO 8601 UTC {@code Z} 문자열로 직렬화된다(컨벤션 §5).
 *
 * <p>필드 추가는 같은 토픽 버전에서 허용(컨슈머는 미지 필드 무시), 호환 깨지는 변경만 v2 토픽 신설(스파이크 결정 1).
 */
public record MilestoneAchievedEvent(
        String eventId,
        String eventType,
        String milestoneType,
        String userPublicId,
        Instant occurredAt,
        String sourceService,
        int schemaVersion) {

    /** event_type은 본 토픽에서 단일 값. */
    public static final String EVENT_TYPE = "MILESTONE_ACHIEVED";

    /** source_service 고정값. */
    public static final String SOURCE_SERVICE = "community-service";

    /** 현 스키마 메이저 버전(토픽 v1). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * 발행 시점 메타(event_id=UUID, occurred_at=now UTC 초단위)를 채워 페이로드를 만든다.
     * 호출 = {@link MilestoneEventPublisher}(AFTER_COMMIT) 한 곳.
     */
    public static MilestoneAchievedEvent of(MilestoneAchieved milestone) {
        return new MilestoneAchievedEvent(
                UUID.randomUUID().toString(),
                EVENT_TYPE,
                milestone.milestoneType().name(),
                milestone.userPublicId(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS),
                SOURCE_SERVICE,
                SCHEMA_VERSION);
    }
}
