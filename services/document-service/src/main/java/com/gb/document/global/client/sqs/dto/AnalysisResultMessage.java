package com.gb.document.global.client.sqs.dto;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskItem;
import com.gb.document.domain.document.entity.RiskLevel;
import com.gb.document.domain.document.entity.WageSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Lambda B → SQS → Consumer 결과 메시지 (스키마 v1.1).
 *
 * <p>SSOT: {@code docs/document-analysis/result-json-schema-agreement.md} §2/§3 (v1.1).
 * Lambda B가 발행한 결과 JSON이 <b>SQS 메시지 본문 그대로</b> 들어와 이 record로 역직렬화된다
 * (envelope 없음 — 라우팅 메타 source/document_public_id는 SQS MessageAttributes로 분리, §1 경고).
 *
 * <p>전역 SNAKE_CASE 전략({@code spring.jackson.property-naming-strategy: SNAKE_CASE})으로
 * camelCase 필드명이 snake_case JSON 키와 매핑된다(CLAUDE.md §5).
 *
 * <h3>nullable 규약(§4)</h3>
 * <ul>
 *   <li>{@link #overallRiskLevel} — 위험 없음/분석 실패 시 {@code null}. risk_items와 연동(§3-2).</li>
 *   <li>wage_summary 하위 {@code monthlyWage}/{@code hourlyWage} — 정보 없음 {@code null}, 0원 명시는 "0"
 *       (재사용하는 {@link WageSummary}는 BigDecimal — Jackson이 string→BigDecimal 변환).</li>
 *   <li>{@link #riskItems} — 위험 없음은 빈 배열 {@code []} (null 아님).</li>
 *   <li>{@link #failedReason} — FAILED일 때만 string, 그 외 {@code null}.</li>
 * </ul>
 *
 * <h3>완전성 검증</h3>
 * <p>스키마는 모든 필드를 필수(O)로 명시하므로, 누락 시 Jackson이 기본값(null/0)으로 채울 수
 * 있어 검증을 listener/service 레이어에서 별도로 수행한다(§7 변경 절차 위반 방지).
 */
public record AnalysisResultMessage(
        String schemaVersion,
        String documentPublicId,
        AnalysisDocumentType analysisDocumentType,
        ProcessingStatus processingStatus,
        RiskLevel overallRiskLevel,
        BigDecimal ocrConfidence,
        WageSummary wageSummary,
        List<RiskItem> riskItems,
        String translatedText,
        String translatedLang,
        String maskedFileUrl,
        String failedReason,
        Instant completedAt
) {
    /** 현재 합의 버전. 메시지 수신 시 일치 확인용. */
    public static final String SUPPORTED_SCHEMA_VERSION = "1.1";

    /** PARTIAL은 결과 일부만 채워졌어도 submission은 COMPLETED로 본다(§5 매핑). */
    public boolean isResultUsable() {
        return processingStatus == ProcessingStatus.COMPLETED
                || processingStatus == ProcessingStatus.PARTIAL;
    }
}
