package com.gb.document.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * MilestoneEventPublisher 단위 테스트 (Phase 3 — BE-7, wallet BE-3 테스트 복제).
 *
 * <p>커밋 후(AFTER_COMMIT) 호출되는 발행기의 계약을 검증한다: ① 토픽/키(user_public_id)/페이로드
 * 스키마(스파이크 결정 1·2) ② 발행 실패(동기/비동기)가 호출자(SQS Consumer 영속화 흐름)로 전파되지
 * 않음(결정 4 — 유실 허용 + ERROR 로그). AFTER_COMMIT 바인딩 자체(롤백 시 미발행)는 Spring 트랜잭션
 * 인프라 소관이라 단위 범위 밖.
 */
@ExtendWith(MockitoExtension.class)
class MilestoneEventPublisherTest {

    private static final String USER = "9b2f1111-2222-3333-4444-555566667777";

    @Mock
    private KafkaTemplate<String, MilestoneAchievedEvent> kafkaTemplate;

    @InjectMocks
    private MilestoneEventPublisher publisher;

    @Test
    @DisplayName("발행: 토픽 document.milestone-achieved.v1, 키=user_public_id, 페이로드는 스파이크 스키마 메타를 채운다")
    @SuppressWarnings("unchecked")
    void publish_토픽_키_페이로드() {
        SendResult<String, MilestoneAchievedEvent> sendResult = mock(SendResult.class);
        given(kafkaTemplate.send(anyString(), anyString(), any(MilestoneAchievedEvent.class)))
                .willReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publish(new MilestoneAchieved(USER, MilestoneType.DOCUMENT_ANALYZED));

        ArgumentCaptor<MilestoneAchievedEvent> captor = ArgumentCaptor.forClass(MilestoneAchievedEvent.class);
        verify(kafkaTemplate).send(eq(MilestoneEventPublisher.TOPIC), eq(USER), captor.capture());

        assertThat(MilestoneEventPublisher.TOPIC).isEqualTo("document.milestone-achieved.v1");

        MilestoneAchievedEvent event = captor.getValue();
        assertThat(event.eventId()).isNotBlank();                       // UUID 발급
        assertThat(event.eventType()).isEqualTo("MILESTONE_ACHIEVED");
        assertThat(event.milestoneType()).isEqualTo("DOCUMENT_ANALYZED");
        assertThat(event.userPublicId()).isEqualTo(USER);
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.sourceService()).isEqualTo("document-service");
        assertThat(event.schemaVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("비동기 발행 실패(브로커 응답 오류): 콜백에서 ERROR 로깅만 — 호출자로 예외가 전파되지 않는다")
    void publish_비동기실패_예외미전파() {
        given(kafkaTemplate.send(anyString(), anyString(), any(MilestoneAchievedEvent.class)))
                .willReturn(CompletableFuture.failedFuture(new KafkaException("broker error")));

        assertThatCode(() -> publisher.publish(
                new MilestoneAchieved(USER, MilestoneType.DOCUMENT_ANALYZED)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("동기 발행 실패(send 자체가 throw — 메타데이터 대기 타임아웃 등): 흡수 후 ERROR 로깅 — 예외 미전파")
    void publish_동기실패_예외미전파() {
        given(kafkaTemplate.send(anyString(), anyString(), any(MilestoneAchievedEvent.class)))
                .willThrow(new KafkaException("max.block.ms timeout"));

        assertThatCode(() -> publisher.publish(
                new MilestoneAchieved(USER, MilestoneType.DOCUMENT_ANALYZED)))
                .doesNotThrowAnyException();
    }
}
