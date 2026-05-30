package com.gb.document.domain.document.service.impl;

import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import com.gb.document.domain.document.service.AnalysisResultIngestService;
import com.gb.document.global.client.sqs.dto.AnalysisResultMessage;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

        // 2) submission 조회. 없으면 poison — 재시도 후 DLQ.
        Document submission = documentRepository.findByPublicId(msg.documentPublicId())
                .orElseThrow(() -> new IllegalStateException(
                        "분석 결과 매칭 실패 — document_public_id=" + msg.documentPublicId()
                                + " 해당 submission 미존재"));

        // 3) document_results UPSERT — submission_id UNIQUE이라 1건만 존재.
        LocalDateTime completedAt = msg.completedAt() == null ? null
                : LocalDateTime.ofInstant(msg.completedAt(), ZoneOffset.UTC);

        documentResultRepository.findBySubmission_Id(submission.getId())
                .ifPresentOrElse(
                        existing -> existing.applyAnalysisResult(
                                msg.analysisDocumentType(),
                                msg.processingStatus(),
                                msg.overallRiskLevel(),
                                msg.ocrConfidence(),
                                msg.wageSummary(),
                                msg.riskItems(),
                                msg.translatedText(),
                                msg.translatedLang(),
                                msg.maskedFileUrl(),
                                msg.failedReason(),
                                completedAt),
                        () -> documentResultRepository.save(DocumentResult.builder()
                                .submission(submission)
                                .analysisDocumentType(msg.analysisDocumentType())
                                .processingStatus(msg.processingStatus())
                                .overallRiskLevel(msg.overallRiskLevel())
                                .ocrConfidence(msg.ocrConfidence())
                                .wageSummary(msg.wageSummary())
                                .riskItems(msg.riskItems())
                                .translatedText(msg.translatedText())
                                .translatedLang(msg.translatedLang())
                                .maskedFileUrl(msg.maskedFileUrl())
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
}
