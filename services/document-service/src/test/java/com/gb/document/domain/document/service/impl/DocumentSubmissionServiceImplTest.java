package com.gb.document.domain.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.document.domain.document.dto.request.SubmitRequest;
import com.gb.document.domain.document.dto.response.DocumentResultResponse;
import com.gb.document.domain.document.dto.response.DocumentStatusResponse;
import com.gb.document.domain.document.dto.response.SubmissionResponse;
import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskLevel;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import com.gb.document.global.client.s3.S3PresignedUrlClient;
import com.gb.document.global.client.s3.S3PresignedUrlClient.IssueUrlResult;
import com.gb.document.global.client.sqs.AnalysisRequestPublisher;
import com.gb.document.global.config.AnalysisProperties;
import com.gb.document.global.exception.code.DocumentErrorCode;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentSubmissionServiceImplTest {

    private static final String OWNER = "user-public-id-A";
    private static final String OTHER = "user-public-id-B";
    private static final String PUBLIC_ID = "doc-public-id-1";

    @Mock DocumentRepository documentRepository;
    @Mock DocumentResultRepository documentResultRepository;
    @Mock S3PresignedUrlClient s3PresignedUrlClient;
    @Mock AnalysisRequestPublisher analysisRequestPublisher;

    @InjectMocks DocumentSubmissionServiceImpl service;

    // Production source가 아닌 dev 기본값을 가정. AnalysisProperties는 @InjectMocks가 자동 주입 못하므로 setter 대신 생성자.
    AnalysisProperties props = productionProps("");

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        // @InjectMocks는 record/생성자 주입 우선이라 그대로 둬도 들어가지만, 명시적으로 reflection으로 박는다.
        Field f = DocumentSubmissionServiceImpl.class.getDeclaredField("analysisProperties");
        f.setAccessible(true);
        f.set(service, props);
    }

    @Test
    @DisplayName("submit: 신규 Document 저장 + Pre-signed URL 발급(메타데이터 키 source/document_id 포함)")
    void submit_정상() {
        SubmitRequest req = new SubmitRequest(AnalysisDocumentType.LABOR_CONTRACT, "contract.pdf");
        Instant exp = Instant.parse("2026-05-29T10:00:00Z");
        Map<String, String> headers = Map.of(
                "Content-Type", "application/octet-stream", "x-amz-meta-source", "development");
        given(s3PresignedUrlClient.issueUploadUrl(anyString(), anyString(), anyMap(), any(Duration.class)))
                .willReturn(new IssueUrlResult("https://mock/url", headers, exp));

        SubmissionResponse res = service.submit(OWNER, req);

        verify(documentRepository).save(any(Document.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metaCap = ArgumentCaptor.forClass(Map.class);
        verify(s3PresignedUrlClient).issueUploadUrl(anyString(), anyString(), metaCap.capture(), any(Duration.class));
        Map<String, String> metadata = metaCap.getValue();
        assertThat(metadata).containsKeys("source", "document_id");
        // dev source일 때는 result_queue_arn이 안 들어가야 한다.
        assertThat(metadata).doesNotContainKey("result_queue_arn");
        assertThat(res.getStatus()).isEqualTo("ANALYZING");
        assertThat(res.getUploadUrl()).isEqualTo("https://mock/url");
        // 업로더가 PUT 시 그대로 보내야 하는 서명 헤더가 응답에 그대로 실린다.
        assertThat(res.getUploadHeaders()).isEqualTo(headers);
        assertThat(res.getExpiresAt()).isEqualTo("2026-05-29T10:00:00Z");
    }

    @Test
    @DisplayName("submit: production source + result_queue_arn 설정 시 메타데이터에 result_queue_arn 포함")
    void submit_production_큐ARN_포함() throws Exception {
        Field f = DocumentSubmissionServiceImpl.class.getDeclaredField("analysisProperties");
        f.setAccessible(true);
        f.set(service, new AnalysisProperties(
                "production",
                "arn:aws:sqs:ap-northeast-2:123:gb-analysis-results-prod",
                "",
                "gb-document-uploads-prod",
                600,
                "ap-northeast-2"));

        SubmitRequest req = new SubmitRequest(AnalysisDocumentType.PAYSLIP, "payslip.pdf");
        given(s3PresignedUrlClient.issueUploadUrl(anyString(), anyString(), anyMap(), any(Duration.class)))
                .willReturn(new IssueUrlResult("u", Map.of(), Instant.now()));

        service.submit(OWNER, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metaCap = ArgumentCaptor.forClass(Map.class);
        verify(s3PresignedUrlClient).issueUploadUrl(anyString(), anyString(), metaCap.capture(), any(Duration.class));
        assertThat(metaCap.getValue())
                .containsEntry("source", "production")
                .containsEntry("result_queue_arn", "arn:aws:sqs:ap-northeast-2:123:gb-analysis-results-prod")
                .containsKey("document_id");
    }

    @Test
    @DisplayName("getStatus: 정상 — ANALYZING 상태면 estimated_minutes=3")
    void getStatus_정상() {
        Document doc = analyzingDoc();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));

        DocumentStatusResponse res = service.getStatus(OWNER, PUBLIC_ID);

        assertThat(res.getStatus()).isEqualTo("ANALYZING");
        assertThat(res.getEstimatedMinutes()).isEqualTo(3);
    }

    @Test
    @DisplayName("getStatus: 미존재 → DOCUMENT4001")
    void getStatus_미존재() {
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(OWNER, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(DocumentErrorCode.DOCUMENT_NOT_FOUND);
        verifyNoInteractions(documentResultRepository);
    }

    @Test
    @DisplayName("getStatus: 다른 소유자 → COMMON4031 (FORBIDDEN)")
    void getStatus_권한없음() {
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(analyzingDoc()));

        assertThatThrownBy(() -> service.getStatus(OTHER, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("getResult: 결과 없음 → COMMON4221 (UNPROCESSABLE_ENTITY)")
    void getResult_결과없음() {
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(analyzingDoc()));
        given(documentResultRepository.findBySubmission_PublicId(PUBLIC_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResult(OWNER, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("getResult: 정상 — 매핑된 응답 반환")
    void getResult_정상() {
        Document doc = completedDoc();
        DocumentResult result = DocumentResult.builder()
                .submission(doc)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(ProcessingStatus.COMPLETED)
                .overallRiskLevel(RiskLevel.HIGH)
                .ocrConfidence(new BigDecimal("0.92"))
                .completedAt(LocalDateTime.parse("2026-05-29T09:00:00"))
                .build();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));
        given(documentResultRepository.findBySubmission_PublicId(PUBLIC_ID)).willReturn(Optional.of(result));

        DocumentResultResponse res = service.getResult(OWNER, PUBLIC_ID);

        assertThat(res.getProcessingStatus()).isEqualTo("COMPLETED");
        assertThat(res.getOverallRiskLevel()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("retry: FAILED 상태면 status=ANALYZING 전환 + publisher 호출")
    void retry_정상() {
        Document doc = failedDoc();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));

        SubmissionResponse res = service.retry(OWNER, PUBLIC_ID);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.ANALYZING);
        assertThat(res.getStatus()).isEqualTo("ANALYZING");
        verify(analysisRequestPublisher).publishRetry(eq(PUBLIC_ID), eq(OWNER), anyString());
    }

    @Test
    @DisplayName("retry: 비-FAILED 상태(ANALYZING)면 COMMON4221, publisher 미호출")
    void retry_비실패상태() {
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(analyzingDoc()));

        assertThatThrownBy(() -> service.retry(OWNER, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(analysisRequestPublisher);
    }

    // ---- helpers ----

    private Document analyzingDoc() {
        return Document.builder()
                .publicId(PUBLIC_ID)
                .userPublicId(OWNER)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .fileName("c.pdf")
                .status(DocumentStatus.ANALYZING)
                .build();
    }

    private Document completedDoc() {
        return Document.builder()
                .publicId(PUBLIC_ID)
                .userPublicId(OWNER)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .fileName("c.pdf")
                .status(DocumentStatus.COMPLETED)
                .build();
    }

    private Document failedDoc() {
        return Document.builder()
                .publicId(PUBLIC_ID)
                .userPublicId(OWNER)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .fileName("c.pdf")
                .status(DocumentStatus.FAILED)
                .build();
    }

    private static AnalysisProperties productionProps(String resultQueueArn) {
        // dev 기본(source=development, queue ARN 빈 값)
        Map<String, String> ignored = new HashMap<>();
        return new AnalysisProperties(
                "development", resultQueueArn, "", "gb-document-uploads-dev", 600, "ap-northeast-2");
    }
}
