package com.gb.document.domain.document.service.impl;

import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskItem;
import com.gb.document.domain.document.entity.RiskLevel;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import com.gb.document.domain.document.service.AnalysisResultIngestService;
import com.gb.document.global.client.sqs.dto.AnalysisResultMessage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 결과 영속화 구현. routing §3 / schema §5 (v1.1) 매핑 SSOT를 따른다.
 *
 * <p>트랜잭션은 메서드 단위로 묶어 results UPSERT와 submissions.status 갱신이 원자적으로 일어나도록 한다.
 * 예외가 던져지면 spring-cloud-aws가 메시지를 삭제하지 않아 visibility timeout 후 재수신되고,
 * 큐의 maxReceiveCount 초과 시 자동으로 DLQ로 이동한다(routing §3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisResultIngestServiceImpl implements AnalysisResultIngestService {

    private final DocumentRepository documentRepository;
    private final DocumentResultRepository documentResultRepository;

    @Override
    @Transactional
    public void ingest(AnalysisResultMessage msg) {
        // 1) 스키마 버전 검증. 합의 v1.1 외 메시지는 양쪽 합의 없이 처리할 수 없으므로 fail-fast(§7).
        if (!AnalysisResultMessage.SUPPORTED_SCHEMA_VERSION.equals(msg.schemaVersion())) {
            throw new IllegalStateException(
                    "지원하지 않는 schema_version=" + msg.schemaVersion()
                            + " (지원: " + AnalysisResultMessage.SUPPORTED_SCHEMA_VERSION + ")");
        }

        // 1-1) 페이로드 무결성 검증(스키마 §3). 구조가 깨진 메시지는 DB 조회 전에 fail-fast → DLQ.
        validateRequiredFields(msg);                        // C: 필수 필드 누락 → 예외(DLQ)
        validateRiskLinkage(msg);                           // B: overall_risk_level ↔ risk_items(§3-2) → 예외(DLQ)
        BigDecimal ocrConfidence = clampOcrConfidence(msg); // A: 범위 밖이면 clamp + WARN(저장 계속)

        // 2) submission 조회. 없으면 poison — 재시도 후 DLQ.
        Document submission = documentRepository.findByPublicId(msg.documentPublicId())
                .orElseThrow(() -> new IllegalStateException(
                        "분석 결과 매칭 실패 — document_public_id=" + msg.documentPublicId()
                                + " 해당 submission 미존재"));

        // 3) document_results UPSERT — submission_id UNIQUE이라 1건만 존재.
        LocalDateTime completedAt = LocalDateTime.ofInstant(msg.completedAt(), ZoneOffset.UTC);
        // 와이어는 풀 URI(masked_file_url), 저장은 키만(s3_masked_key) — database.md 스키마 SSOT.
        String s3MaskedKey = s3UriToKey(msg.maskedFileUrl());

        documentResultRepository.findBySubmission_Id(submission.getId())
                .ifPresentOrElse(
                        existing -> existing.applyAnalysisResult(
                                msg.analysisDocumentType(),
                                msg.processingStatus(),
                                msg.overallRiskLevel(),
                                ocrConfidence,
                                msg.wageSummary(),
                                msg.riskItems(),
                                msg.translatedText(),
                                msg.translatedLang(),
                                s3MaskedKey,
                                msg.failedReason(),
                                completedAt),
                        () -> documentResultRepository.save(DocumentResult.builder()
                                .submission(submission)
                                .analysisDocumentType(msg.analysisDocumentType())
                                .processingStatus(msg.processingStatus())
                                .overallRiskLevel(msg.overallRiskLevel())
                                .ocrConfidence(ocrConfidence)
                                .wageSummary(msg.wageSummary())
                                .riskItems(msg.riskItems())
                                .translatedText(msg.translatedText())
                                .translatedLang(msg.translatedLang())
                                .s3MaskedKey(s3MaskedKey)
                                .failedReason(msg.failedReason())
                                .completedAt(completedAt)
                                .build())
                );

        // 4) submission.status 동기화. PARTIAL은 결과는 받아왔으므로 사용자 입장에서 COMPLETED(§5 매핑).
        if (msg.processingStatus() == ProcessingStatus.FAILED) {
            submission.markFailed();
        } else { // COMPLETED, PARTIAL
            submission.markCompleted();
        }

        log.info("[sqs-consumer] 분석 결과 적용 documentPublicId={} processingStatus={}",
                msg.documentPublicId(), msg.processingStatus());
    }

    /**
     * C) 필수 필드 누락 검증 — 스키마 §3에서 구조적으로 non-null이어야 하는 필드. 누락은 계약 위반이라
     * 예외 → 컨테이너가 ack하지 않아 재시도 후 DLQ. nullable 필드(overall_risk_level/failed_reason)는
     * 제외(§3·§4).
     */
    private void validateRequiredFields(AnalysisResultMessage msg) {
        requireField(msg, msg.analysisDocumentType(), "analysis_document_type");
        requireField(msg, msg.processingStatus(), "processing_status");
        requireField(msg, msg.ocrConfidence(), "ocr_confidence");
        requireField(msg, msg.wageSummary(), "wage_summary");
        requireField(msg, msg.riskItems(), "risk_items");
        requireField(msg, msg.completedAt(), "completed_at");
    }

    private void requireField(AnalysisResultMessage msg, Object value, String fieldName) {
        if (value == null) {
            throw new IllegalStateException(
                    "필수 필드 누락 — " + fieldName + " (document_public_id=" + msg.documentPublicId() + ")");
        }
    }

    /**
     * B) overall_risk_level ↔ risk_items 연동 규칙(스키마 §3-2). Lambda B 프롬프트가 강제하고 Consumer는
     * 검증만 한다. 위반은 구조 깨짐이라 예외 → DLQ.
     * <ul>
     *   <li>risk_items 비었으면 overall_risk_level == null</li>
     *   <li>risk_items 있으면 overall_risk_level == max(risk_items[].risk_level) (HIGH&gt;MEDIUM&gt;LOW)</li>
     * </ul>
     */
    private void validateRiskLinkage(AnalysisResultMessage msg) {
        List<RiskItem> riskItems = msg.riskItems();
        RiskLevel overall = msg.overallRiskLevel();

        if (riskItems.isEmpty()) {
            if (overall != null) {
                throw new IllegalStateException(
                        "risk 연동 규칙 위반(§3-2) — risk_items 비었는데 overall_risk_level=" + overall
                                + " (document_public_id=" + msg.documentPublicId() + ")");
            }
            return;
        }

        // 자연 순서(enum 선언 LOW<MEDIUM<HIGH)가 심각도와 일치하므로 compareTo로 max 산출.
        RiskLevel expected = null;
        for (RiskItem item : riskItems) {
            RiskLevel level = item.riskLevel();
            if (level == null) {
                throw new IllegalStateException(
                        "risk_items[].risk_level 누락 (document_public_id=" + msg.documentPublicId() + ")");
            }
            if (expected == null || level.compareTo(expected) > 0) {
                expected = level;
            }
        }
        if (overall != expected) {
            throw new IllegalStateException(
                    "risk 연동 규칙 위반(§3-2) — overall_risk_level=" + overall + " 이지만 max(risk_items)="
                            + expected + " (document_public_id=" + msg.documentPublicId() + ")");
        }
    }

    /**
     * 와이어 포맷 {@code masked_file_url}("s3://bucket/key")에서 키만 추출 — Lambda B의
     * {@code _s3_uri_to_key()}와 대칭. s3:// 스킴이 아니면(이미 키 형태 등) 그대로 저장해
     * 메시지를 버리지 않는다(비치명 필드). null이면 null(마스킹본 미생성 케이스).
     * 키가 없는 malformed s3:// URI("s3://bucket" 등)는 null로 정규화 — 원문을 키로 저장하면
     * 조회 시 존재하지 않는 키로 presigned URL이 발급되므로, "마스킹본 미보유"(masked_file_url=null)로 처리한다.
     */
    private String s3UriToKey(String maskedFileUrl) {
        if (maskedFileUrl == null || !maskedFileUrl.startsWith("s3://")) {
            return maskedFileUrl;
        }
        int keyStart = maskedFileUrl.indexOf('/', "s3://".length());
        if (keyStart < 0 || keyStart == maskedFileUrl.length() - 1) {
            log.warn("[sqs-consumer] masked_file_url 키 추출 실패 — null(마스킹본 미보유)로 저장: {}", maskedFileUrl);
            return null;
        }
        return maskedFileUrl.substring(keyStart + 1);
    }

    /**
     * A) ocr_confidence 범위 [0.00, 1.00] 검증(스키마 §3). 표시 전용 수치라 범위 밖이어도 메시지를 버리지
     * 않고 경계값으로 clamp + WARN 로그 후 저장을 계속한다. (B·C와 달리 비치명.)
     */
    private BigDecimal clampOcrConfidence(AnalysisResultMessage msg) {
        BigDecimal raw = msg.ocrConfidence();
        if (raw.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("[sqs-consumer] ocr_confidence={} < 0 → 0.00으로 clamp documentPublicId={}",
                    raw, msg.documentPublicId());
            return BigDecimal.ZERO;
        }
        if (raw.compareTo(BigDecimal.ONE) > 0) {
            log.warn("[sqs-consumer] ocr_confidence={} > 1 → 1.00으로 clamp documentPublicId={}",
                    raw, msg.documentPublicId());
            return BigDecimal.ONE;
        }
        return raw;
    }
}
