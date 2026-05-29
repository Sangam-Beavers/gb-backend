package com.gb.document.domain.document.entity;

import java.math.BigDecimal;
import java.util.List;

/**
 * 급여 요약(분석 결과 wage_summary 필드). api-spec.md §3 (v1.1) SSOT.
 *
 * <p>{@link DocumentResult#getWageSummary()}에서 JSON 컬럼으로 직렬화·역직렬화된다.
 * 금액은 conventions §0에 따라 {@link BigDecimal}로 보관하고 응답에서는 string으로 직렬화한다.
 */
public record WageSummary(
        String currencyCode,
        BigDecimal monthlyWage,
        BigDecimal hourlyWage,
        List<Deduction> deductions
) {
    public record Deduction(String name, BigDecimal amount) {}
}
