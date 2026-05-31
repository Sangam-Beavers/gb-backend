package com.gb.document.domain.document.service;

import com.gb.document.global.client.sqs.dto.AnalysisResultMessage;

/**
 * SQS Consumer가 수신한 분석 결과(v1.1)를 DB로 영속화하는 진입점.
 *
 * <p>HTTP 경로가 없어 {@link DocumentSubmissionService}와 분리한다. 결과 페이로드 ↔ DB 매핑은
 * {@code docs/document-analysis/result-json-schema-agreement.md} §5(v1.1) SSOT.
 */
public interface AnalysisResultIngestService {

    /**
     * 결과 메시지를 적용한다. 같은 {@code document_public_id}로 결과가 이미 존재하면 갱신(UPSERT) —
     * SQS at-least-once 재수신 및 retry 후 성공 케이스 모두 멱등하게 처리.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>{@code document_submissions}를 {@code document_public_id}로 조회 — 없으면 예외(메시지는 DLQ)</li>
     *   <li>{@code document_results} UPSERT — 1:1 UNIQUE(submission_id)이라 INSERT/UPDATE 분기</li>
     *   <li>{@code document_submissions.status} 동기화 — COMPLETED/PARTIAL → COMPLETED, FAILED → FAILED</li>
     * </ol>
     */
    void ingest(AnalysisResultMessage message);
}
