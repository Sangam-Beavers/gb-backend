package com.gb.document.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.document.global.client.sqs.AnalysisResultListener;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.SqsContainerOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * spring-cloud-aws Consumer 컨테이너 옵션 회귀 테스트.
 *
 * <p>스키마 §1·routing §3 약속 "회귀 테스트로 잠근다"의 이행. {@code SqsContainerOptions
 * .messageAttributeNames}를 명시하지 않으면 spring-cloud-aws는 {@code ReceiveMessage}에 attribute
 * 리스트를 보내지 않아 본문은 도착하지만 attribute가 누락된 채 들어온다(기본값이 {@code ["ALL"]}이라
 * 도착하긴 하지만, 우리는 명시적 화이트리스트로 잠가 라이브러리 기본값 변경에 흔들리지 않게 한다).
 *
 * <p>{@code @SqsListener} 어노테이션에는 {@code messageAttributeNames} 속성이 없으므로(컴파일 에러)
 * 컨테이너 옵션 레벨에서만 설정 가능. 그 설정이 정확히 {@code source}/{@code document_public_id}로
 * 잠겼는지를 여기서 검증한다 — Lambda B ↔ Consumer 사이 라우팅 메타 합의가 어긋나지 않도록.
 *
 * <h3>검증 방식</h3>
 * <p>{@code SqsMessageListenerContainerFactory}에 옵션을 다시 꺼내는 공개 getter가 없어
 * {@link io.awspring.cloud.sqs.config.AbstractMessageListenerContainerFactory#configure(java.util.function.Consumer)}
 * 를 한 번 더 호출해 빌더에 접근한다. {@code SqsContainerOptionsBuilder.build()}는 새
 * {@code SqsContainerOptions}만 만들고 빌더 state를 바꾸지 않으므로(sources 확인) 안전.
 */
class AwsSqsConsumerConfigTest {

    @Test
    @DisplayName("factory가 ReceiveMessage 시 source / document_public_id 두 attribute를 정확히 요청해야 한다")
    void messageAttributeNames_화이트리스트가_정확히_적용된다() {
        AwsSqsConsumerConfig config = new AwsSqsConsumerConfig();
        // 컨테이너를 실제로 띄우진 않으므로 메서드 호출 없음 → mock으로 충분.
        SqsAsyncClient sqsAsyncClient = Mockito.mock(SqsAsyncClient.class);

        SqsMessageListenerContainerFactory<Object> factory =
                config.defaultSqsListenerContainerFactory(sqsAsyncClient);

        // configure 콜백 재호출로 빌더에 접근 → build()로 옵션 스냅샷.
        SqsContainerOptions[] snapshot = new SqsContainerOptions[1];
        factory.configure(builder -> snapshot[0] = builder.build());

        assertThat(snapshot[0].getMessageAttributeNames())
                .as("Lambda B가 SendMessage로 싣는 라우팅 attribute 두 개를 정확히 화이트리스트로 잠가야 한다")
                .containsExactlyInAnyOrder(
                        AnalysisResultListener.ATTR_SOURCE,
                        AnalysisResultListener.ATTR_DOCUMENT_PUBLIC_ID);
    }
}
