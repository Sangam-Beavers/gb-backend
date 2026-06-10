package com.gb.document.global.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 마일스톤 달성 이벤트의 Kafka 발행기 (Phase 3 — BE-7, wallet BE-3 패턴 복제).
 *
 * <p>도메인 서비스가 비즈니스 트랜잭션 안에서 발행한 내부 이벤트({@link MilestoneAchieved})를
 * <b>커밋 후</b>({@code AFTER_COMMIT})에 받아 토픽 {@value #TOPIC}으로 전송한다 — 커밋 전 발행
 * (롤백됐는데 이벤트만 나가는 유령 이벤트)을 구조적으로 차단한다(스파이크 결정 4). 트랜잭션이
 * 롤백되면 이 리스너는 호출되지 않는다.
 *
 * <p><b>메시지 키 = user_public_id</b> — 같은 유저의 이벤트는 같은 파티션 = 유저 단위 순서 보장(결정 2).
 *
 * <p><b>발행 실패 = ERROR 로그만</b>(유실 허용 + 보정 전제, Outbox 보류 — 결정 4). 등급은 표시용 파생
 * 데이터고 원본(document_results)에서 재산정 가능하므로, 발행 실패가 분석 결과 영속화 흐름
 * (이미 커밋 완료 — SQS Consumer 경로)에 영향을 주면 안 된다:
 * <ul>
 *   <li>비동기 실패(브로커 응답 오류 등) → send 콜백({@code whenComplete})에서 ERROR 로깅.</li>
 *   <li>동기 실패(브로커 미가용으로 메타데이터 대기 타임아웃 등 — {@code send()} 자체가 throw) →
 *       try/catch로 흡수 후 ERROR 로깅. AFTER_COMMIT 리스너의 예외는 호출 스레드(SQS 리스너 스레드)로
 *       전파되면 이미 커밋된 메시지가 재수신·DLQ로 갈 수 있으므로 반드시 여기서 삼킨다.
 *       대기 상한은 {@code max.block.ms}로 캡(KafkaProducerConfig).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneEventPublisher {

    /** 발행 토픽 — {발행 서비스 도메인}.{이벤트}.v{스키마 메이저 버전} (스파이크 결정 1, 발행자 소유). */
    public static final String TOPIC = "document.milestone-achieved.v1";

    private final KafkaTemplate<String, MilestoneAchievedEvent> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(MilestoneAchieved milestone) {
        MilestoneAchievedEvent event = MilestoneAchievedEvent.of(milestone);
        try {
            kafkaTemplate.send(TOPIC, event.userPublicId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("마일스톤 이벤트 발행 실패(비동기) — 보정 대상. topic={}, milestone_type={}, "
                                            + "user_public_id={}, event_id={}",
                                    TOPIC, event.milestoneType(), event.userPublicId(), event.eventId(), ex);
                        } else if (log.isDebugEnabled()) {
                            log.debug("마일스톤 이벤트 발행 완료. topic={}, partition={}, offset={}, event_id={}",
                                    TOPIC, result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(), event.eventId());
                        }
                    });
        } catch (RuntimeException e) {
            log.error("마일스톤 이벤트 발행 실패(동기) — 보정 대상. topic={}, milestone_type={}, "
                            + "user_public_id={}, event_id={}",
                    TOPIC, event.milestoneType(), event.userPublicId(), event.eventId(), e);
        }
    }
}
