package com.gb.document.domain.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
import com.gb.document.global.client.s3.S3ObjectClient;
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
    private static final String S3_KEY = "original/2026-06-04/" + PUBLIC_ID + "/c.pdf";

    @Mock DocumentRepository documentRepository;
    @Mock DocumentResultRepository documentResultRepository;
    @Mock S3PresignedUrlClient s3PresignedUrlClient;
    @Mock S3ObjectClient s3ObjectClient;
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
    @DisplayName("submit: 신규 Document 저장 + Pre-signed URL 발급(메타데이터 키 source/document_id/analysis_document_type 포함)")
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
        // 백엔드가 analysis_document_type을 심는다(account-a-contract-notice.md 결정 3).
        assertThat(metadata).containsEntry("analysis_document_type", "LABOR_CONTRACT");
        // dev source일 때는 result_queue_arn이 안 들어가야 한다.
        assertThat(metadata).doesNotContainKey("result_queue_arn");
        // user_lang은 추후 과제로 보류 — 현재 백엔드는 심지 않는다.
        assertThat(metadata).doesNotContainKey("user_lang");
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
                "ap-northeast-2",
                false,
                "",
                30));

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
                .containsEntry("analysis_document_type", "PAYSLIP")
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
    @DisplayName("getStatus: ANALYZING인데 결과(COMPLETED) 도착 — lazy-sync로 COMPLETED 반환, estimated=0")
    void getStatus_결과도착시_lazySync_완료() {
        // dev 경로: Lambda B가 results를 직접 INSERT — Consumer를 안 거치므로 status가 ANALYZING으로 남는 케이스.
        Document doc = analyzingDoc();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));
        given(documentResultRepository.findBySubmission_PublicId(PUBLIC_ID))
                .willReturn(Optional.of(resultOf(doc, ProcessingStatus.COMPLETED)));

        DocumentStatusResponse res = service.getStatus(OWNER, PUBLIC_ID);

        assertThat(res.getStatus()).isEqualTo("COMPLETED");
        assertThat(res.getEstimatedMinutes()).isZero();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    @DisplayName("getStatus: ANALYZING인데 결과(FAILED) 도착 — lazy-sync로 FAILED 반환")
    void getStatus_결과도착시_lazySync_실패() {
        Document doc = analyzingDoc();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));
        given(documentResultRepository.findBySubmission_PublicId(PUBLIC_ID))
                .willReturn(Optional.of(resultOf(doc, ProcessingStatus.FAILED)));

        DocumentStatusResponse res = service.getStatus(OWNER, PUBLIC_ID);

        assertThat(res.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("getStatus: ANALYZING인데 결과(PARTIAL) 도착 — Consumer §4와 동일하게 COMPLETED 매핑")
    void getStatus_결과도착시_lazySync_부분성공은_완료매핑() {
        Document doc = analyzingDoc();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));
        given(documentResultRepository.findBySubmission_PublicId(PUBLIC_ID))
                .willReturn(Optional.of(resultOf(doc, ProcessingStatus.PARTIAL)));

        DocumentStatusResponse res = service.getStatus(OWNER, PUBLIC_ID);

        assertThat(res.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("getStatus: 이미 종료 상태(COMPLETED)면 결과 조회(lazy-sync) 자체를 안 한다")
    void getStatus_종료상태면_결과조회_생략() {
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(completedDoc()));

        DocumentStatusResponse res = service.getStatus(OWNER, PUBLIC_ID);

        assertThat(res.getStatus()).isEqualTo("COMPLETED");
        verifyNoInteractions(documentResultRepository);
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
        // s3_masked_key 미보유 → presign 호출 없이 masked_file_url=null (api-spec §3 "미생성 시 null")
        assertThat(res.getMaskedFileUrl()).isNull();
        verify(s3PresignedUrlClient, never()).issueDownloadUrl(anyString(), any());
    }

    @Test
    @DisplayName("getResult: s3_masked_key 보유 시 presigned GET URL을 생성해 masked_file_url로 응답")
    void getResult_마스킹본_presigned_GET() {
        Document doc = completedDoc();
        DocumentResult result = DocumentResult.builder()
                .submission(doc)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(ProcessingStatus.COMPLETED)
                .overallRiskLevel(RiskLevel.HIGH)
                .ocrConfidence(new BigDecimal("0.92"))
                .s3MaskedKey("masked/doc-public-id-1.txt")
                .completedAt(LocalDateTime.parse("2026-05-29T09:00:00"))
                .build();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));
        given(documentResultRepository.findBySubmission_PublicId(PUBLIC_ID)).willReturn(Optional.of(result));
        given(s3PresignedUrlClient.issueDownloadUrl(eq("masked/doc-public-id-1.txt"), any(Duration.class)))
                .willReturn("https://s3.signed.example/masked/doc-public-id-1.txt?X-Amz-Signature=x");

        DocumentResultResponse res = service.getResult(OWNER, PUBLIC_ID);

        assertThat(res.getMaskedFileUrl())
                .isEqualTo("https://s3.signed.example/masked/doc-public-id-1.txt?X-Amz-Signature=x");
    }

    @Test
    @DisplayName("retry: S3 원본 존재 — ANALYZING 전환 + 저장된 s3Key로 publisher 호출, URL 재발급 없음")
    void retry_원본존재_재트리거() {
        Document doc = failedDoc();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));
        given(s3ObjectClient.objectExists(S3_KEY)).willReturn(true);

        SubmissionResponse res = service.retry(OWNER, PUBLIC_ID);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.ANALYZING);
        assertThat(res.getStatus()).isEqualTo("ANALYZING");
        assertThat(res.getUploadUrl()).isNull();
        verify(analysisRequestPublisher).publishRetry(PUBLIC_ID, OWNER, S3_KEY);
        verify(s3PresignedUrlClient, never()).issueUploadUrl(anyString(), anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("retry: S3 원본 미존재(미업로드 만료) — COMMON4221, FAILED 유지, publisher 미호출 (새 제출로 유도)")
    void retry_원본미존재_거절() {
        Document doc = failedDoc();
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));
        given(s3ObjectClient.objectExists(S3_KEY)).willReturn(false);

        assertThatThrownBy(() -> service.retry(OWNER, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.UNPROCESSABLE_ENTITY);

        // 거절 시 상태는 FAILED 그대로(사용자는 POST /documents로 새로 제출), 재트리거·URL 발급 없음.
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        verifyNoInteractions(analysisRequestPublisher);
        verify(s3PresignedUrlClient, never()).issueUploadUrl(anyString(), anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("retry: s3_key 컬럼 도입 이전 행(null)은 createdAt 날짜로 키를 복원해 사용")
    void retry_레거시행_createdAt날짜로_키복원() throws Exception {
        Document doc = failedDocWithoutS3Key();
        setCreatedAt(doc, LocalDateTime.parse("2026-05-29T09:00:00"));
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(doc));
        // 제출일(5/29) 날짜로 복원돼야 한다 — 오늘 날짜로 재조립하면 원본 키와 어긋난다(날짜 드리프트 버그).
        String legacyKey = "original/2026-05-29/" + PUBLIC_ID + "/c.pdf";
        given(s3ObjectClient.objectExists(legacyKey)).willReturn(true);

        service.retry(OWNER, PUBLIC_ID);

        verify(analysisRequestPublisher).publishRetry(PUBLIC_ID, OWNER, legacyKey);
    }

    @Test
    @DisplayName("retry: 비-FAILED 상태(ANALYZING)면 COMMON4221, publisher·S3 미호출")
    void retry_비실패상태() {
        given(documentRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(analyzingDoc()));

        assertThatThrownBy(() -> service.retry(OWNER, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(analysisRequestPublisher, s3ObjectClient, s3PresignedUrlClient);
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
                .s3Key(S3_KEY)
                .build();
    }

    /** s3_key 컬럼 도입 이전에 저장된 행 모사 — s3Key=null. */
    private Document failedDocWithoutS3Key() {
        return Document.builder()
                .publicId(PUBLIC_ID)
                .userPublicId(OWNER)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .fileName("c.pdf")
                .status(DocumentStatus.FAILED)
                .build();
    }

    /** lazy-sync 테스트용 최소 결과 — processing_status만 의미 있다. */
    private static DocumentResult resultOf(Document doc, ProcessingStatus processingStatus) {
        return DocumentResult.builder()
                .submission(doc)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(processingStatus)
                .ocrConfidence(new BigDecimal("0.90"))
                .completedAt(LocalDateTime.parse("2026-06-05T09:00:00"))
                .build();
    }

    /** BaseEntity.createdAt은 auditing 전용이라 builder가 없다 — 단위 테스트에서만 reflection으로 주입. */
    private static void setCreatedAt(Document doc, LocalDateTime createdAt) throws Exception {
        Field f = doc.getClass().getSuperclass().getDeclaredField("createdAt");
        f.setAccessible(true);
        f.set(doc, createdAt);
    }

    private static AnalysisProperties productionProps(String resultQueueArn) {
        // dev 기본(source=development, queue ARN 빈 값)
        Map<String, String> ignored = new HashMap<>();
        return new AnalysisProperties(
                "development", resultQueueArn, "", "gb-document-uploads-dev", 600, "ap-northeast-2",
                false, "", 30);
    }
}
