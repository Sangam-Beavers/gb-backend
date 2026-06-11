package com.gb.member.domain.milestone.consumer;

import com.gb.member.domain.milestone.dto.event.MilestoneAchievedEvent;
import com.gb.member.domain.milestone.service.MilestoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 마일스톤 달성 이벤트 Kafka Consumer (Phase 2 — BE-4 / Phase 3 — BE-7).
 *
 * <p>얇은 어댑터 — 처리 본문(멱등 INSERT + 등급 재계산, 한 트랜잭션)은
 * {@link MilestoneService#recordMilestone}에 있다(@KafkaListener 메서드에 @Transactional을 직접 걸지
 * 않고 서비스 경계에서 tx를 연다 — 컨트롤러가 로직 없이 서비스를 호출하는 규칙과 동일 사상).
 *
 * <p>구독 토픽(스파이크 결정 1 — 발행자 소유, member는 구독만):
 * <ul>
 *   <li>{@value #WALLET_TOPIC} — wallet-service 발행 (Phase 2)</li>
 *   <li>{@value #DOCUMENT_TOPIC} — document-service 발행 (Phase 3 BE-7, DOCUMENT_ANALYZED)</li>
 * </ul>
 * 페이로드 스키마({@link MilestoneAchievedEvent})는 모든 발행자가 공유하는 동일 계약이므로
 * 리스너 하나로 처리한다. 발행자 구분이 필요하면 {@code event.sourceService()}로 식별.
 *
 * <p>에러 처리(스파이크 결정 5)는 컨테이너 팩토리(KafkaConsumerConfig)의 {@code DefaultErrorHandler}가
 * 담당한다: 처리 실패 3회 재시도(backoff 1s) 후 DLT로 발행.
 * 역직렬화 실패(poison pill)는 ErrorHandlingDeserializer가 잡아 재시도 없이 곧장 DLT행.
 *
 * <p>{@code autoStartup} 프로퍼티: test 프로파일은 브로커가 없어 기본 false로 두고
 * (application-test.yml), EmbeddedKafka 통합 테스트만 인라인 프로퍼티로 켠다. dev/stage/prod는 기본 true.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneEventConsumer {

    /** wallet-service 발행 토픽 (Phase 2 — BE-4). */
    public static final String WALLET_TOPIC = "wallet.milestone-achieved.v1";

    /** document-service 발행 토픽 (Phase 3 — BE-7, DOCUMENT_ANALYZED). */
    public static final String DOCUMENT_TOPIC = "document.milestone-achieved.v1";

    /** community-service 발행 토픽 (Phase 3 — BE-8, COMMUNITY_ACTIVE). */
    public static final String COMMUNITY_TOPIC = "community.milestone-achieved.v1";

    /** Consumer group — {서비스명}.{용도} (스파이크 결정 5). */
    public static final String GROUP_ID = "member.trust-grade";

    private final MilestoneService milestoneService;

    @KafkaListener(
            topics = {WALLET_TOPIC, DOCUMENT_TOPIC, COMMUNITY_TOPIC},
            groupId = GROUP_ID,
            containerFactory = "milestoneKafkaListenerContainerFactory",
            autoStartup = "${gb.kafka.milestone-consumer.auto-startup:true}")
    public void consume(MilestoneAchievedEvent event) {
        // ErrorHandlingDeserializer 실패 레코드(null 페이로드)는 리스너 도달 전에 에러 핸들러가 DLT로
        // 보내지만, tombstone 등 정상 null도 방어한다(처리할 내용 없음 — 재시도 무의미).
        if (event == null) {
            log.warn("null 페이로드 수신 — 스킵.");
            return;
        }
        log.debug("마일스톤 이벤트 수신. milestone_type={}, user_public_id={}, source_service={}, event_id={}",
                event.milestoneType(), event.userPublicId(), event.sourceService(), event.eventId());
        milestoneService.recordMilestone(event);
    }
}
