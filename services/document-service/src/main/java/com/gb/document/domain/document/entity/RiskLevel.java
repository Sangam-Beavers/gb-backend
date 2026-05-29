package com.gb.document.domain.document.entity;

/**
 * 분석 결과의 overall_risk_level / risk_items[].risk_level 값. api-spec.md §3 (v1.1) SSOT.
 *
 * <p>v1.1에서 NONE이 제거되었으므로 null은 "위험도 미평가"를 의미한다(컬럼 NULL 허용).
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
