package com.gb.document.domain.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskItem;
import com.gb.document.domain.document.entity.RiskLevel;
import com.gb.document.domain.document.entity.WageSummary;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import com.gb.document.global.client.sqs.dto.AnalysisResultMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Consumer 영속화 서비스 단위 테스트.
 *
 * <p>스키마 §5 매핑 + processing_status ↔ submissions.status 동기화 + 멱등성(재수신/UPSERT)
 * 분기를 검증한다. spring-cloud-aws 컨테이너는 별도 통합 테스트 영역.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisResultIngestServiceImplTest {

    private static final String DOC_PUBLIC_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock DocumentRepository documentRepository;
    @Mock DocumentResultRepository documentResultRepository;

    @InjectMocks AnalysisResultIngestServiceImpl service;

    @Test
    @DisplayName("COMPLETED: 신규 결과 INSERT + submissions.status COMPLETED")
    void COMPLETED_정상수신() {
        Document submission = spy(analyzingDoc());
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.empty());

        service.ingest(message(ProcessingStatus.COMPLETED, RiskLevel.HIGH, null));

        verify(documentResultRepository).save(any(DocumentResult.class));
        verify(submission).markCompleted();
        verify(submission, never()).markFailed();
    }

    @Test
    @DisplayName("FAILED: 결과 INSERT + submissions.status FAILED + failed_reason 보존")
    void FAILED_사유보존() {
        Document submission = spy(analyzingDoc());
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.empty());

        service.ingest(message(ProcessingStatus.FAILED, null, "OCR 단계 실패"));

        verify(documentResultRepository).save(any(DocumentResult.class));
        verify(submission).markFailed();
        verify(submission, never()).markCompleted();
    }

    @Test
    @DisplayName("PARTIAL: results.processing_status는 PARTIAL이지만 submissions.status는 COMPLETED(§5)")
    void PARTIAL_은_사용자관점_COMPLETED() {
        Document submission = spy(analyzingDoc());
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.empty());

        service.ingest(message(ProcessingStatus.PARTIAL, RiskLevel.MEDIUM, "번역 단계 실패"));

        verify(submission).markCompleted();
        verify(submission, never()).markFailed();
    }

    @Test
    @DisplayName("at-least-once 재수신: 동일 submission의 결과가 이미 있으면 UPDATE(applyAnalysisResult), 새 INSERT 없음")
    void 멱등성_재수신은_UPDATE() {
        Document submission = analyzingDoc();
        DocumentResult existing = spy(DocumentResult.builder()
                .submission(submission)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(ProcessingStatus.FAILED)
                .overallRiskLevel(null)
                .ocrConfidence(new BigDecimal("0.50"))
                .build());

        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        // submission.getId()가 builder-만든 mock entity에선 null이라 anyLong()은 매처 불일치 → any()로 통일.
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.of(existing));

        // retry 후 성공으로 같은 메시지 다시 수신(COMPLETED 결과).
        service.ingest(message(ProcessingStatus.COMPLETED, RiskLevel.HIGH, null));

        // 기존 row를 in-place 갱신했고, 신규 INSERT는 일어나지 않았다.
        verify(existing, times(1)).applyAnalysisResult(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(documentResultRepository, never()).save(any(DocumentResult.class));
    }

    @Test
    @DisplayName("매칭 실패: document_public_id에 해당하는 submission이 없으면 예외 → 컨테이너가 ack 안 함 → DLQ")
    void 매칭실패는_예외() {
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(message(ProcessingStatus.COMPLETED, RiskLevel.HIGH, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DOC_PUBLIC_ID)
                .hasMessageContaining("미존재");
    }

    @Test
    @DisplayName("schema_version 불일치: 합의 외 버전은 §7 절차 없이 처리 금지 → 예외 → DLQ")
    void 스키마버전_불일치는_예외() {
        AnalysisResultMessage older = new AnalysisResultMessage(
                "1.0",
                DOC_PUBLIC_ID,
                AnalysisDocumentType.LABOR_CONTRACT,
                ProcessingStatus.COMPLETED,
                RiskLevel.HIGH,
                new BigDecimal("0.92"),
                wage(),
                riskItems(),
                "...",
                "ko",
                "s3://b/k",
                null,
                Instant.parse("2026-05-29T09:00:00Z"));

        assertThatThrownBy(() -> service.ingest(older))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema_version");
    }

    @Test
    @DisplayName("B 위반: risk_items 비었는데 overall=HIGH → 예외 + DB 미접근 (DLQ)")
    void risk연동_빈배열인데_overall있으면_예외() {
        AnalysisResultMessage msg = msg(
                ProcessingStatus.COMPLETED, RiskLevel.HIGH, List.of(),
                new BigDecimal("0.9"), AnalysisDocumentType.LABOR_CONTRACT);

        assertThatThrownBy(() -> service.ingest(msg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk 연동");

        verifyNoInteractions(documentRepository, documentResultRepository);
    }

    @Test
    @DisplayName("B 위반: overall이 max(risk_items)와 불일치(items=[HIGH,MEDIUM], overall=LOW) → 예외 (DLQ)")
    void risk연동_overall이_max와_불일치하면_예외() {
        AnalysisResultMessage msg = msg(
                ProcessingStatus.COMPLETED, RiskLevel.LOW,
                List.of(new RiskItem(RiskLevel.HIGH, "제8조", "x"),
                        new RiskItem(RiskLevel.MEDIUM, "제9조", "y")),
                new BigDecimal("0.9"), AnalysisDocumentType.LABOR_CONTRACT);

        assertThatThrownBy(() -> service.ingest(msg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk 연동");

        verifyNoInteractions(documentRepository, documentResultRepository);
    }

    @Test
    @DisplayName("B 정상: risk_items=[] + overall=null → 저장 진행")
    void risk연동_빈배열_overall_null이면_정상저장() {
        Document submission = analyzingDoc();
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.empty());

        service.ingest(msg(ProcessingStatus.COMPLETED, null, List.of(),
                new BigDecimal("0.9"), AnalysisDocumentType.LABOR_CONTRACT));

        verify(documentResultRepository).save(any(DocumentResult.class));
    }

    @Test
    @DisplayName("C 위반: 필수 필드(analysis_document_type) null → 예외 + DB 미접근 (DLQ)")
    void 필수필드_누락이면_예외() {
        AnalysisResultMessage msg = msg(
                ProcessingStatus.COMPLETED, RiskLevel.HIGH,
                List.of(new RiskItem(RiskLevel.HIGH, "제8조", "x")),
                new BigDecimal("0.9"), null);

        assertThatThrownBy(() -> service.ingest(msg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("analysis_document_type");

        verifyNoInteractions(documentRepository, documentResultRepository);
    }

    @Test
    @DisplayName("A: ocr_confidence > 1 → 1.00으로 clamp 후 저장(예외 없음)")
    void ocr_상한초과는_clamp되어_저장() {
        Document submission = analyzingDoc();
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.empty());

        service.ingest(msg(ProcessingStatus.COMPLETED, RiskLevel.HIGH,
                List.of(new RiskItem(RiskLevel.HIGH, "제8조", "x")),
                new BigDecimal("1.5"), AnalysisDocumentType.LABOR_CONTRACT));

        ArgumentCaptor<DocumentResult> captor = ArgumentCaptor.forClass(DocumentResult.class);
        verify(documentResultRepository).save(captor.capture());
        assertThat(captor.getValue().getOcrConfidence()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("A: ocr_confidence < 0 → 0.00으로 clamp 후 저장(예외 없음)")
    void ocr_하한미만은_clamp되어_저장() {
        Document submission = analyzingDoc();
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.empty());

        service.ingest(msg(ProcessingStatus.COMPLETED, RiskLevel.HIGH,
                List.of(new RiskItem(RiskLevel.HIGH, "제8조", "x")),
                new BigDecimal("-0.1"), AnalysisDocumentType.LABOR_CONTRACT));

        ArgumentCaptor<DocumentResult> captor = ArgumentCaptor.forClass(DocumentResult.class);
        verify(documentResultRepository).save(captor.capture());
        assertThat(captor.getValue().getOcrConfidence()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("masked_file_url(풀 s3:// URI)은 s3_masked_key(키만)로 변환 저장 — database.md 스키마")
    void maskedFileUrl은_키로_변환되어_저장() {
        Document submission = analyzingDoc();
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.empty());

        // 헬퍼 메시지의 masked_file_url = "s3://gb-document-masked-test/2026-05-29/x.png"
        service.ingest(message(ProcessingStatus.COMPLETED, RiskLevel.HIGH, null));

        ArgumentCaptor<DocumentResult> captor = ArgumentCaptor.forClass(DocumentResult.class);
        verify(documentResultRepository).save(captor.capture());
        assertThat(captor.getValue().getS3MaskedKey()).isEqualTo("2026-05-29/x.png");
    }

    @Test
    @DisplayName("키 없는 malformed s3:// URI는 null(마스킹본 미보유)로 정규화 저장 — 깨진 presigned URL 발급 방지")
    void malformed_s3_URI는_null로_저장() {
        Document submission = analyzingDoc();
        given(documentRepository.findByPublicId(DOC_PUBLIC_ID)).willReturn(Optional.of(submission));
        given(documentResultRepository.findBySubmission_Id(any())).willReturn(Optional.empty());

        service.ingest(new AnalysisResultMessage(
                "1.1", DOC_PUBLIC_ID, AnalysisDocumentType.LABOR_CONTRACT,
                ProcessingStatus.COMPLETED, RiskLevel.HIGH, new BigDecimal("0.92"),
                wage(), riskItems(), "번역 전문", "ko",
                "s3://gb-document-masked-test", null,
                Instant.parse("2026-05-29T09:00:00Z")));

        ArgumentCaptor<DocumentResult> captor = ArgumentCaptor.forClass(DocumentResult.class);
        verify(documentResultRepository).save(captor.capture());
        assertThat(captor.getValue().getS3MaskedKey()).isNull();
    }

    // ---- helpers ----

    private Document analyzingDoc() {
        return Document.builder()
                .publicId(DOC_PUBLIC_ID)
                .userPublicId("user-A")
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .fileName("c.pdf")
                .status(DocumentStatus.ANALYZING)
                .build();
    }

    private AnalysisResultMessage message(ProcessingStatus status, RiskLevel overall, String failedReason) {
        return new AnalysisResultMessage(
                "1.1",
                DOC_PUBLIC_ID,
                AnalysisDocumentType.LABOR_CONTRACT,
                status,
                overall,
                new BigDecimal("0.92"),
                wage(),
                riskItemsFor(overall),
                "번역 전문",
                "ko",
                "s3://gb-document-masked-test/2026-05-29/x.png",
                failedReason,
                Instant.parse("2026-05-29T09:00:00Z"));
    }

    private AnalysisResultMessage msg(ProcessingStatus status, RiskLevel overall,
            List<RiskItem> items, BigDecimal ocr, AnalysisDocumentType docType) {
        return new AnalysisResultMessage(
                "1.1", DOC_PUBLIC_ID, docType, status, overall, ocr,
                wage(), items, "번역 전문", "ko",
                "s3://gb-document-masked-test/2026-05-29/x.png", null,
                Instant.parse("2026-05-29T09:00:00Z"));
    }

    private WageSummary wage() {
        return new WageSummary(
                "KRW",
                new BigDecimal("2000000"),
                new BigDecimal("9620"),
                List.of(new WageSummary.Deduction("national_pension", new BigDecimal("90000"))));
    }

    /** 연동 규칙(§3-2)을 만족하도록 overall에 맞춰 risk_items를 구성한다(null→빈 배열). */
    private List<RiskItem> riskItemsFor(RiskLevel overall) {
        return overall == null ? List.of() : List.of(new RiskItem(overall, "제8조", "최저임금 미달"));
    }

    private List<RiskItem> riskItems() {
        return List.of(new RiskItem(RiskLevel.HIGH, "제8조", "최저임금 미달"));
    }
}
