package com.gb.document.global.client.sqs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * dev 프로파일용 Mock — SQS 호출 없이 로그만 남긴다. retry 흐름을 로컬에서 검증할 때 사용.
 */
@Slf4j
@Component
@Profile("dev")
public class MockAnalysisRequestPublisher implements AnalysisRequestPublisher {

    @Override
    public void publishRetry(String documentPublicId, String userPublicId, String s3Key) {
        log.info("[mock-sqs] publishRetry documentPublicId={} userPublicId={} s3Key={}",
                documentPublicId, userPublicId, s3Key);
    }
}
