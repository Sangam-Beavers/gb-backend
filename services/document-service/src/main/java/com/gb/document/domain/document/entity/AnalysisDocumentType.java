package com.gb.document.domain.document.entity;

/**
 * 분석 대상 문서의 종류. api-spec.md §2 (v1.1) SSOT.
 *
 * <p>conventions.md §10 — SCREAMING_SNAKE_CASE. 도메인별로 분석 결과 필드(예: 급여 요약/위험 조항)의
 * 의미가 갈리므로 도메인을 명시적으로 구분한다.
 */
public enum AnalysisDocumentType {
    LABOR_CONTRACT,
    PAYSLIP,
    EMPLOYMENT_CONTRACT
}
