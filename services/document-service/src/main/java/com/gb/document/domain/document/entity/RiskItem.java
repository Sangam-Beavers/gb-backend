package com.gb.document.domain.document.entity;

/**
 * 위험 조항 항목(분석 결과 risk_items 배열의 한 원소). api-spec.md §3 (v1.1) SSOT.
 *
 * <p>{@link DocumentResult#getRiskItems()}에서 JSON 컬럼으로 직렬화·역직렬화된다.
 */
public record RiskItem(
        RiskLevel riskLevel,
        String clause,
        String description
) {}
