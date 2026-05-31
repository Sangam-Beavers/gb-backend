package com.gb.document.global.client.sqs.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 결과 메시지 DTO ↔ v1.1 JSON 라운드트립 검증.
 *
 * <p>스키마 §2 정본 샘플 3종(정상/위험없음/PARTIAL)을 Jackson(SNAKE_CASE) 설정 그대로 역직렬화해
 * 본문 == 스키마 SSOT 규약이 깨지지 않는지 잠근다. application.yaml의 SNAKE_CASE 전역 설정과
 * 동일 옵션으로 ObjectMapper를 구성한다.
 */
class AnalysisResultMessageJsonTest {

    /** application.yaml의 SNAKE_CASE 전역 설정 + Java Time 모듈을 동일 구성으로 재현. */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    @DisplayName("v1.1 정상 샘플 — 모든 필드 매핑 + ISO 8601 UTC 완료시각 + BigDecimal 금액(string)")
    void v11_정상샘플() throws Exception {
        String json = """
            {
              "schema_version": "1.1",
              "document_public_id": "550e8400-e29b-41d4-a716-446655440000",
              "analysis_document_type": "LABOR_CONTRACT",
              "processing_status": "COMPLETED",
              "overall_risk_level": "HIGH",
              "ocr_confidence": 0.92,
              "wage_summary": {
                "currency_code": "KRW",
                "monthly_wage": "2000000",
                "hourly_wage": "9620",
                "deductions": [
                  { "name": "national_pension", "amount": "90000" }
                ]
              },
              "risk_items": [
                {
                  "risk_level": "HIGH",
                  "clause": "제8조",
                  "description": "최저임금 미달 — 시급 9,620원 기준 미충족"
                }
              ],
              "translated_text": "...",
              "translated_lang": "ko",
              "masked_file_url": "s3://gb-document-masked-prod/2026-05-29/abc.png",
              "failed_reason": null,
              "completed_at": "2026-05-29T09:00:00Z"
            }
            """;

        AnalysisResultMessage msg = mapper.readValue(json, AnalysisResultMessage.class);

        assertThat(msg.schemaVersion()).isEqualTo("1.1");
        assertThat(msg.documentPublicId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(msg.analysisDocumentType()).isEqualTo(AnalysisDocumentType.LABOR_CONTRACT);
        assertThat(msg.processingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(msg.overallRiskLevel()).isEqualTo(RiskLevel.HIGH);
        // ocr_confidence는 DECIMAL(3,2) 범위 [0.00, 1.00] 고정 (스키마 §3).
        assertThat(msg.ocrConfidence()).isEqualByComparingTo(new BigDecimal("0.92"));
        // 금액은 string(decimal)로 송신, BigDecimal로 역직렬화.
        assertThat(msg.wageSummary().monthlyWage()).isEqualByComparingTo(new BigDecimal("2000000"));
        assertThat(msg.wageSummary().hourlyWage()).isEqualByComparingTo(new BigDecimal("9620"));
        assertThat(msg.wageSummary().deductions()).hasSize(1);
        assertThat(msg.wageSummary().deductions().get(0).name()).isEqualTo("national_pension");
        assertThat(msg.wageSummary().deductions().get(0).amount()).isEqualByComparingTo(new BigDecimal("90000"));
        assertThat(msg.riskItems()).hasSize(1);
        assertThat(msg.riskItems().get(0).riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(msg.riskItems().get(0).clause()).isEqualTo("제8조");
        assertThat(msg.translatedLang()).isEqualTo("ko");
        assertThat(msg.maskedFileUrl()).startsWith("s3://");
        assertThat(msg.failedReason()).isNull();
        assertThat(msg.completedAt()).isEqualTo(Instant.parse("2026-05-29T09:00:00Z"));

        // isResultUsable: COMPLETED는 true.
        assertThat(msg.isResultUsable()).isTrue();
    }

    @Test
    @DisplayName("v1.1 위험 없음 샘플 — overall_risk_level=null + risk_items=[] 연동 규칙(§3-2)")
    void v11_위험없음() throws Exception {
        String json = """
            {
              "schema_version": "1.1",
              "document_public_id": "11111111-1111-1111-1111-111111111111",
              "analysis_document_type": "LABOR_CONTRACT",
              "processing_status": "COMPLETED",
              "overall_risk_level": null,
              "ocr_confidence": 0.88,
              "wage_summary": {
                "currency_code": "KRW",
                "monthly_wage": "2500000",
                "hourly_wage": "12000",
                "deductions": []
              },
              "risk_items": [],
              "translated_text": "",
              "translated_lang": "ko",
              "masked_file_url": "s3://gb-document-masked-prod/2026-05-29/none.png",
              "failed_reason": null,
              "completed_at": "2026-05-29T10:00:00Z"
            }
            """;

        AnalysisResultMessage msg = mapper.readValue(json, AnalysisResultMessage.class);

        assertThat(msg.overallRiskLevel()).isNull();
        assertThat(msg.riskItems()).isEmpty();
        assertThat(msg.wageSummary().deductions()).isEmpty();
    }

    @Test
    @DisplayName("v1.1 PARTIAL 샘플 — 일부 단계 실패 + failed_reason 보존")
    void v11_PARTIAL() throws Exception {
        String json = """
            {
              "schema_version": "1.1",
              "document_public_id": "22222222-2222-2222-2222-222222222222",
              "analysis_document_type": "LABOR_CONTRACT",
              "processing_status": "PARTIAL",
              "overall_risk_level": "MEDIUM",
              "ocr_confidence": 0.75,
              "wage_summary": {
                "currency_code": "KRW",
                "monthly_wage": "2000000",
                "hourly_wage": null,
                "deductions": []
              },
              "risk_items": [
                { "risk_level": "MEDIUM", "clause": "-", "description": "수당 규정 모호" }
              ],
              "translated_text": "",
              "translated_lang": "ko",
              "masked_file_url": "s3://gb-document-masked-prod/2026-05-29/partial.png",
              "failed_reason": "번역 단계 실패: Bedrock translation timeout",
              "completed_at": "2026-05-29T11:00:00Z"
            }
            """;

        AnalysisResultMessage msg = mapper.readValue(json, AnalysisResultMessage.class);

        assertThat(msg.processingStatus()).isEqualTo(ProcessingStatus.PARTIAL);
        // monthly_wage="0"과 null은 다른 의미(스키마 §4). hourly_wage는 정보 없음 → null.
        assertThat(msg.wageSummary().hourlyWage()).isNull();
        assertThat(msg.wageSummary().monthlyWage()).isEqualByComparingTo(new BigDecimal("2000000"));
        assertThat(msg.failedReason()).contains("번역 단계 실패");
        // PARTIAL도 isResultUsable=true → submissions.status는 COMPLETED로 매핑.
        assertThat(msg.isResultUsable()).isTrue();
    }
}
