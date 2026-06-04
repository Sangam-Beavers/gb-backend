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

    /**
     * 다운로드용 Pre-signed GET URL을 발급한다. 결과 조회 응답의 {@code masked_file_url}처럼
     * DB에 키만 저장된 오브젝트(s3_masked_key)를 클라이언트가 직접 받게 할 때 사용한다(api-spec.md §3).
     *
     * @param key S3 오브젝트 키 (예: masked/{public_id}.txt)
     * @param ttl URL 유효시간.
     * @return Pre-signed GET URL (GET은 서명 헤더 재전송이 불필요해 URL만 반환)
     */
    String issueDownloadUrl(String key, Duration ttl);

    /**
     * 발급 결과.
     *
     * @param url           Pre-signed PUT URL(응답 upload_url 그대로 노출).
     * @param signedHeaders 서명에 포함된 헤더(Content-Type, x-amz-meta-*). 업로더는 PUT 시 이 헤더를
     *                      이름+값까지 그대로 다시 보내야 서명이 일치한다(안 보내면 403). host는 HTTP
     *                      클라이언트가 자동 설정하므로 제외한다. 응답 upload_headers로 노출.
     * @param expiresAt     URL 만료 시각(응답 expires_at 그대로 노출).
     */
    record IssueUrlResult(String url, Map<String, String> signedHeaders, Instant expiresAt) {}
}
