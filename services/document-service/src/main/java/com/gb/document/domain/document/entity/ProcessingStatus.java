package com.gb.document.domain.document.entity;

/**
 * 분석 결과 처리 상태. result JSON v1.1의 processing_status 필드 SSOT.
 *
 * <p>Document.status({@link DocumentStatus}: ANALYZING/COMPLETED/FAILED)와는 의미가 다르다.
 * PARTIAL은 일부 항목만 추출됐을 때를 표현하며, 이 경우 submission.status는 COMPLETED로 매핑된다
 * (결과 자체는 받아왔으므로). 매핑 규약: docs/document-analysis/result-queue-routing.md §4.
 */
public enum ProcessingStatus {
    COMPLETED,
    FAILED,
    PARTIAL
}
