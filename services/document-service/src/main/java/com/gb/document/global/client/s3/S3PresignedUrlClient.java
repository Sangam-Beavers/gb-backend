package com.gb.document.global.client.s3;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * S3 Pre-signed PUT URL 발급 클라이언트. CLAUDE.md §7 — 외부 시스템 호출은 인터페이스로 추상화하고
 * Profile로 Mock/Real 구현을 분리한다.
 *
 * <p>호출 측은 사용자가 그 URL로 파일을 PUT 업로드한 직후 S3 오브젝트에 박힐 메타데이터를 함께 전달한다.
 * Lambda A는 이 메타데이터를 읽어 결과 경로(SQS vs 온프렘 직결)를 분기하므로 키 이름이 정확히 일치해야 한다.
 * (key 이름: source / document_id / result_queue_arn — docs/document-analysis/ai-pipeline.md)
 */
public interface S3PresignedUrlClient {

    /**
     * 업로드용 Pre-signed PUT URL을 발급한다.
     *
     * @param key         업로드될 S3 오브젝트 키 (예: uploads/yyyy-MM-dd/{public_id}/{file_name})
     * @param contentType Content-Type 헤더. 사용자가 PUT 시 같은 값을 보내야 서명이 일치한다.
     * @param metadata    S3 오브젝트 메타데이터로 박힐 (key, value) 맵. 빈 값은 호출 측에서 제외.
     * @param ttl         URL 유효시간.
     */
    IssueUrlResult issueUploadUrl(String key, String contentType, Map<String, String> metadata, Duration ttl);

    /** 발급 결과 — URL과 만료 시각(응답 expires_at 그대로 노출). */
    record IssueUrlResult(String url, Instant expiresAt) {}
}
