package com.gb.document.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 분석 파이프라인 연동 설정 (gb.analysis.*).
 *
 * <p>docs/document-analysis/ai-pipeline.md, result-queue-routing.md.
 * source 값으로 Lambda A가 결과 경로(SQS vs 온프렘 직결)를 분기하므로 키 이름이 정확히 일치해야 한다.
 *
 * @param source                   "development"(dev 온프렘 직결) 또는 "production"(stage/prod SQS 경유)
 * @param resultQueueArn           production 계열에서만 사용. S3 메타데이터에 주입돼 Lambda A가 결과를 보낼 큐.
 * @param requestQueueUrl          retry 시 Lambda A 재트리거용 요청 큐 URL. 빈 값이면 publish 스킵.
 * @param uploadBucket             Pre-signed PUT URL이 가리킬 S3 버킷.
 * @param uploadUrlExpiresSeconds  Pre-signed URL 유효시간(초). 기본 600(10분).
 * @param awsRegion                AWS 리전.
 */
@ConfigurationProperties(prefix = "gb.analysis")
public record AnalysisProperties(
        String source,
        String resultQueueArn,
        String requestQueueUrl,
        String uploadBucket,
        int uploadUrlExpiresSeconds,
        String awsRegion
) {
    /** production 계열(stage/prod)에서만 result_queue_arn을 S3 메타데이터에 주입한다. */
    public boolean isProductionSource() {
        return "production".equalsIgnoreCase(source);
    }
}
