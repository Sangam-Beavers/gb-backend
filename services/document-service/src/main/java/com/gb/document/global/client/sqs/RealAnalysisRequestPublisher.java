package com.gb.document.global.client.sqs;

import com.gb.document.global.config.AnalysisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * 운영(!dev)용 — 분석 요청 큐(gb.analysis.request-queue-url)로 재시도 메시지를 publish한다.
 * Lambda A는 이 큐를 구독해 동일 S3 키의 원본을 다시 처리한다.
 */
@Slf4j
@Component
@Profile("!dev")
@RequiredArgsConstructor
public class RealAnalysisRequestPublisher implements AnalysisRequestPublisher {

    private final SqsClient sqsClient;
    private final AnalysisProperties properties;

    @Override
    public void publishRetry(String documentPublicId, String userPublicId, String s3Key) {
        String queueUrl = properties.requestQueueUrl();
        if (queueUrl == null || queueUrl.isBlank()) {
            log.warn("[sqs] request-queue-url 미설정 — retry publish 스킵 documentPublicId={}", documentPublicId);
            return;
        }
        String body = """
                {"document_public_id":"%s","user_public_id":"%s","s3_key":"%s","reason":"retry"}
                """.formatted(documentPublicId, userPublicId, s3Key);
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());
    }
}
