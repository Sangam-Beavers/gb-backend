package com.gb.document.global.config;

import com.gb.document.global.client.sqs.AnalysisResultListener;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * 결과 수신 SQS Consumer 컨테이너 설정 (spring-cloud-aws 3.4.x).
 *
 * <p>활성 조건: {@code gb.analysis.consumer-enabled=true} (stage/prod). dev/test는 빈 등록 자체를 생략.
 *
 * <h3>왜 별도 팩토리가 필요한가</h3>
 * <p>spring-cloud-aws 3.x {@code @SqsListener}는 {@code messageAttributeNames}를 어노테이션 속성으로
 * 받지 않는다(컴파일 에러). 어떤 attribute를 {@code ReceiveMessage}로 요청할지는 컨테이너 옵션 레벨에서
 * 설정해야 하므로, {@link SqsMessageListenerContainerFactory}를 직접 만들어 두 attribute(
 * {@code source}, {@code document_public_id})를 명시 요청한다. 빈 이름을
 * {@code defaultSqsListenerContainerFactory}로 두면 auto-config가 만드는 동명의 빈을 대체한다.
 *
 * <p>리스너에서는 {@code @Header(SqsHeaders.SQS_MA_HEADER_PREFIX + name)}으로 attribute를 읽는다 —
 * spring-cloud-aws가 SQS attribute를 메시지 헤더로 매핑할 때 접두사 {@code Sqs_MA_}를 붙이기 때문.
 *
 * <p>관련 SSOT: {@code docs/document-analysis/result-queue-routing.md} §3.
 */
@Configuration
@ConditionalOnProperty(name = "gb.analysis.consumer-enabled", havingValue = "true")
public class AwsSqsConsumerConfig {

    /**
     * auto-config 기본 팩토리({@code defaultSqsListenerContainerFactory})를 대체.
     * 두 attribute를 명시 요청하도록 {@link io.awspring.cloud.sqs.listener.SqsContainerOptions}를 설정.
     */
    @Bean
    public SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory(
            SqsAsyncClient sqsAsyncClient) {
        return SqsMessageListenerContainerFactory.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .configure(options -> options.messageAttributeNames(List.of(
                        AnalysisResultListener.ATTR_SOURCE,
                        AnalysisResultListener.ATTR_DOCUMENT_PUBLIC_ID)))
                .build();
    }
}
