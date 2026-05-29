package com.gb.document.domain.document.entity;

/**
 * 문서 분석 진행 상태.
 *
 * <p>값은 분석 API 명세(SSOT)의 진행 상태 조회 응답을 따른다
 * (docs/document-analysis/api-spec.md §2 — ANALYZING / COMPLETED / FAILED).
 * conventions.md §10 — SCREAMING_SNAKE_CASE.
 *
 * <p>참고: 결과 상세 응답의 {@code processing_status}(COMPLETED/FAILED/PARTIAL)와는 다른 필드다.
 */
public enum DocumentStatus {
    ANALYZING,
    COMPLETED,
    FAILED
}
