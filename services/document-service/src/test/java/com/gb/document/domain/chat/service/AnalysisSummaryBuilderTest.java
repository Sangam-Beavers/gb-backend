package com.gb.document.domain.chat.service;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskItem;
import com.gb.document.domain.document.entity.RiskLevel;
import com.gb.document.domain.document.entity.WageSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnalysisSummaryBuilder} 단위 테스트 — 순수 조립기라 스프링 컨텍스트/Mock 불필요.
 * 규칙 SSOT: ai-chatbot-mcp.md §6 (500자 상한, riskItems 상위 5건, 미완료 시 null).
 */
class AnalysisSummaryBuilderTest {

    private final AnalysisSummaryBuilder builder = new AnalysisSummaryBuilder();

    @Test
    @DisplayName("COMPLETED 결과는 문서유형·위험도·급여·위험 항목을 모두 담은 요약을 만든다")
    void 완료된_결과_전체_요약() {
        DocumentResult result = DocumentResult.builder()
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(ProcessingStatus.COMPLETED)
                .overallRiskLevel(RiskLevel.HIGH)
                .wageSummary(new WageSummary("KRW",
                        new BigDecimal("1600000"), new BigDecimal("7655"),
                        List.of(new WageSummary.Deduction("기숙사비", new BigDecimal("200000")))))
                .riskItems(List.of(
                        new RiskItem(RiskLevel.HIGH, "제4조 임금", "최저임금 미달"),
                        new RiskItem(RiskLevel.MEDIUM, "제6조 근로시간", "주 50시간 초과")))
                .build();

        String summary = builder.build(result);

        assertThat(summary)
                .contains("문서유형: 근로계약서")
                .contains("종합 위험도: HIGH")
                .contains("급여: 월 1,600,000 / 시급 7,655 (KRW)")
                .contains("공제: 기숙사비 200,000")
                .contains("위험 항목 2건")
                .contains("[HIGH] 제4조 임금 — 최저임금 미달")
                .contains("[MEDIUM] 제6조 근로시간 — 주 50시간 초과");
        assertThat(summary.length()).isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("COMPLETED가 아니면(FAILED/PARTIAL) null — 챗봇은 요약 없이 일반 응대")
    void 미완료_결과는_null() {
        DocumentResult failed = DocumentResult.builder()
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(ProcessingStatus.FAILED)
                .failedReason("OCR 실패")
                .build();
        DocumentResult partial = DocumentResult.builder()
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(ProcessingStatus.PARTIAL)
                .build();

        assertThat(builder.build(failed)).isNull();
        assertThat(builder.build(partial)).isNull();
        assertThat(builder.build(null)).isNull();
    }

    @Test
    @DisplayName("위험도 null은 '미평가', 급여 없음은 급여 문장 생략, 위험 항목 없음은 '없음' 표기")
    void 누락_필드_처리() {
        DocumentResult result = DocumentResult.builder()
                .analysisDocumentType(AnalysisDocumentType.PAYSLIP)
                .processingStatus(ProcessingStatus.COMPLETED)
                .build();

        String summary = builder.build(result);

        assertThat(summary)
                .contains("문서유형: 급여명세서")
                .contains("종합 위험도: 미평가")
                .contains("위험 항목: 없음")
                .doesNotContain("급여:");
    }

    @Test
    @DisplayName("위험 항목은 위험도 내림차순 상위 5건만, 초과분은 '외 N건'으로 축약")
    void 위험_항목_상위_5건() {
        List<RiskItem> items = List.of(
                new RiskItem(RiskLevel.LOW, "L1", "낮음1"),
                new RiskItem(RiskLevel.LOW, "L2", "낮음2"),
                new RiskItem(RiskLevel.HIGH, "H1", "높음1"),
                new RiskItem(RiskLevel.MEDIUM, "M1", "중간1"),
                new RiskItem(RiskLevel.HIGH, "H2", "높음2"),
                new RiskItem(RiskLevel.MEDIUM, "M2", "중간2"),
                new RiskItem(RiskLevel.LOW, "L3", "낮음3"));
        DocumentResult result = DocumentResult.builder()
                .analysisDocumentType(AnalysisDocumentType.EMPLOYMENT_CONTRACT)
                .processingStatus(ProcessingStatus.COMPLETED)
                .riskItems(items)
                .build();

        String summary = builder.build(result);

        assertThat(summary)
                .contains("위험 항목 7건")
                .contains("[HIGH] H1").contains("[HIGH] H2")
                .contains("[MEDIUM] M1").contains("[MEDIUM] M2")
                .contains("(외 2건)");
        // 상위 5건 컷 — LOW 3건 중 1건만 포함된다(정렬 후 5번째 자리)
        assertThat(summary).satisfiesAnyOf(
                s -> assertThat(s).contains("[LOW] L1").doesNotContain("[LOW] L2").doesNotContain("[LOW] L3"),
                s -> assertThat(s).contains("[LOW] L2").doesNotContain("[LOW] L1").doesNotContain("[LOW] L3"),
                s -> assertThat(s).contains("[LOW] L3").doesNotContain("[LOW] L1").doesNotContain("[LOW] L2"));
        // HIGH가 LOW보다 앞에 와야 한다(내림차순)
        assertThat(summary.indexOf("[HIGH]")).isLessThan(summary.indexOf("[LOW]"));
    }

    @Test
    @DisplayName("긴 결과도 500자 상한으로 절단된다")
    void 오백자_상한_절단() {
        List<RiskItem> items = IntStream.range(0, 5)
                .mapToObj(i -> new RiskItem(RiskLevel.HIGH,
                        "아주 긴 조항 제목 " + i + " ".repeat(3) + "조항".repeat(30),
                        "아주 긴 위험 설명 ".repeat(20)))
                .toList();
        DocumentResult result = DocumentResult.builder()
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(ProcessingStatus.COMPLETED)
                .overallRiskLevel(RiskLevel.HIGH)
                .riskItems(items)
                .build();

        String summary = builder.build(result);

        assertThat(summary.length()).isLessThanOrEqualTo(500);
        assertThat(summary).endsWith("…");
    }
}
