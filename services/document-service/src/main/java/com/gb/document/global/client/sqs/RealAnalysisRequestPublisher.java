package com.gb.document.global.client.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    @Override
    public void publishRetry(String documentPublicId, String userPublicId, String s3Key) {
        String queueUrl = properties.requestQueueUrl();
        if (queueUrl == null || queueUrl.isBlank()) {
            log.error("[sqs] request-queue-url 미설정 — retry publish 실패 documentPublicId={}", documentPublicId);
            throw new IllegalStateException(
                    "request-queue-url 미설정 — retry publish 불가 documentPublicId=" + documentPublicId);
        }
        // s3Key에 사용자 파일명(따옴표 가능)이 들어가므로 수동 문자열이 아닌 직렬화로 이스케이프한다.
        String body;
        try {
            body = objectMapper.writeValueAsString(
                    new RetryMessage(documentPublicId, userPublicId, s3Key, "retry"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "retry 메시지 직렬화 실패 documentPublicId=" + documentPublicId, e);
        }
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());
    }

    /** 분석 요청 큐로 보내는 재시도 메시지. 전역 SNAKE_CASE 전략으로 직렬화된다. */
    private record RetryMessage(String documentPublicId, String userPublicId, String s3Key, String reason) {}
}
