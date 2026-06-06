package com.gb.document.domain.chat.service;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskItem;
import com.gb.document.domain.document.entity.WageSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 분석 결과(document_results) → 챗봇 첫 턴 주입용 압축 요약 조립기. 정본: ai-chatbot-mcp.md §6.
 *
 * <p>규칙(정본 그대로):
 * <ul>
 *   <li><b>~500자 상한</b> — 전문이 아닌 압축 요약(토큰 절약). 전문 참조는 §10 {@code search_document_detail} 확장.</li>
 *   <li>{@code riskItems}는 위험도 내림차순 <b>상위 5건만</b>, 초과분은 "외 N건"으로 축약.</li>
 *   <li>{@code translatedText} 미사용(토큰 절약).</li>
 *   <li>{@code processingStatus != COMPLETED}면 <b>null</b> — 챗봇은 요약 없이 일반 응대(에러코드 신설 금지).
 *       PARTIAL은 필드 신뢰도가 보장되지 않아 제외하며, 포함이 필요해지면 정본 갱신 후 완화한다.</li>
 * </ul>
 *
 * <p>엔티티만 받아 문자열을 만드는 순수 조립기다(DB 접근 없음 — 조회는 호출자 책임).
 */
@Component
public class AnalysisSummaryBuilder {

    private static final int MAX_LENGTH = 500;
    private static final int MAX_RISK_ITEMS = 5;

    /**
     * @param result 분석 결과 엔티티 (null 허용)
     * @return 압축 요약. 요약 불가(결과 없음/미완료)면 null — 페이로드에서 analysis_summary 생략
     */
    public String build(DocumentResult result) {
        if (result == null || result.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("문서유형: ").append(documentTypeLabel(result.getAnalysisDocumentType())).append('.');
        sb.append(" 종합 위험도: ")
                .append(result.getOverallRiskLevel() == null ? "미평가" : result.getOverallRiskLevel().name())
                .append('.');
        appendWage(sb, result.getWageSummary());
        appendRiskItems(sb, result.getRiskItems());
        return truncate(sb.toString());
    }

    private String documentTypeLabel(AnalysisDocumentType type) {
        if (type == null) {
            return "미상";
        }
        return switch (type) {
            case LABOR_CONTRACT -> "근로계약서";
            case PAYSLIP -> "급여명세서";
            case EMPLOYMENT_CONTRACT -> "고용계약서";
        };
    }

    private void appendWage(StringBuilder sb, WageSummary wage) {
        if (wage == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (wage.monthlyWage() != null) {
            parts.add("월 " + formatAmount(wage.monthlyWage()));
        }
        if (wage.hourlyWage() != null) {
            parts.add("시급 " + formatAmount(wage.hourlyWage()));
        }
        if (parts.isEmpty()) {
            return;
        }
        sb.append(" 급여: ").append(String.join(" / ", parts));
        if (wage.currencyCode() != null) {
            sb.append(" (").append(wage.currencyCode()).append(')');
        }
        if (wage.deductions() != null && !wage.deductions().isEmpty()) {
            List<String> deductions = wage.deductions().stream()
                    .filter(d -> d != null && d.name() != null)
                    .map(d -> d.amount() == null ? d.name() : d.name() + " " + formatAmount(d.amount()))
                    .toList();
            if (!deductions.isEmpty()) {
                sb.append(", 공제: ").append(String.join(" / ", deductions));
            }
        }
        sb.append('.');
    }

    private void appendRiskItems(StringBuilder sb, List<RiskItem> riskItems) {
        if (riskItems == null || riskItems.isEmpty()) {
            sb.append(" 위험 항목: 없음.");
            return;
        }
        // 위험도 내림차순(HIGH→LOW, 미평가는 마지막) 상위 5건만 — ai-chatbot-mcp.md §6
        List<RiskItem> top = riskItems.stream()
                .sorted(Comparator.comparingInt(
                        (RiskItem it) -> it.riskLevel() == null ? -1 : it.riskLevel().ordinal()).reversed())
                .limit(MAX_RISK_ITEMS)
                .toList();
        List<String> lines = top.stream()
                .map(it -> "[" + (it.riskLevel() == null ? "미평가" : it.riskLevel().name()) + "] "
                        + nullSafe(it.clause()) + " — " + nullSafe(it.description()))
                .toList();
        sb.append(" 위험 항목 ").append(riskItems.size()).append("건: ").append(String.join(" / ", lines));
        if (riskItems.size() > MAX_RISK_ITEMS) {
            sb.append(" (외 ").append(riskItems.size() - MAX_RISK_ITEMS).append("건)");
        }
        sb.append('.');
    }

    /** BigDecimal → 천 단위 구분 문자열. DecimalFormat은 스레드 안전하지 않아 호출마다 생성한다. */
    private String formatAmount(BigDecimal amount) {
        return new DecimalFormat("#,##0.####").format(amount.stripTrailingZeros());
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String summary) {
        if (summary.length() <= MAX_LENGTH) {
            return summary;
        }
        return summary.substring(0, MAX_LENGTH - 1) + "…";
    }
}
