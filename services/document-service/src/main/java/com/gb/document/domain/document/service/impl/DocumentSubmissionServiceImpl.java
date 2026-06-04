package com.gb.document.domain.document.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.document.domain.document.dto.request.SubmitRequest;
import com.gb.document.domain.document.dto.response.DocumentResultResponse;
import com.gb.document.domain.document.dto.response.DocumentStatusResponse;
import com.gb.document.domain.document.dto.response.DocumentSummaryResponse;
import com.gb.document.domain.document.dto.response.SubmissionResponse;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import com.gb.document.domain.document.service.DocumentSubmissionService;
import com.gb.document.global.client.s3.S3PresignedUrlClient;
import com.gb.document.global.client.sqs.AnalysisRequestPublisher;
import com.gb.document.global.config.AnalysisProperties;
import com.gb.document.global.exception.code.DocumentErrorCode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentSubmissionServiceImpl implements DocumentSubmissionService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final DocumentRepository documentRepository;
    private final DocumentResultRepository documentResultRepository;
    private final S3PresignedUrlClient s3PresignedUrlClient;
    private final AnalysisRequestPublisher analysisRequestPublisher;
    private final AnalysisProperties analysisProperties;

    @Override
    @Transactional
    public SubmissionResponse submit(String userPublicId, SubmitRequest request) {
        String publicId = UUID.randomUUID().toString();
        Document document = Document.builder()
                .publicId(publicId)
                .userPublicId(userPublicId)
                .analysisDocumentType(request.analysisDocumentType())
                .fileName(request.fileName())
                .status(DocumentStatus.ANALYZING)
                .build();
        documentRepository.save(document);

        String s3Key = buildS3Key(publicId, request.fileName());
        Map<String, String> metadata = buildS3Metadata(document);
        Duration ttl = Duration.ofSeconds(analysisProperties.uploadUrlExpiresSeconds());

        S3PresignedUrlClient.IssueUrlResult issued = s3PresignedUrlClient.issueUploadUrl(
                s3Key, DEFAULT_CONTENT_TYPE, metadata, ttl);

        return SubmissionResponse.forSubmit(
                document, issued.url(), issued.signedHeaders(), issued.expiresAt());
    }

    @Override
    public DocumentStatusResponse getStatus(String userPublicId, String publicId) {
        Document document = loadOwned(userPublicId, publicId);
        return DocumentStatusResponse.from(document);
    }

    @Override
    public DocumentResultResponse getResult(String userPublicId, String publicId) {
        loadOwned(userPublicId, publicId); // 존재(404) + 소유자(403) 검증 — 결과는 publicId로 조회한다.
        DocumentResult result = documentResultRepository.findBySubmission_PublicId(publicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNPROCESSABLE_ENTITY));
        // DB에는 키(s3_masked_key)만 저장 — 응답 masked_file_url은 조회 시점 Pre-signed GET URL(api-spec §3).
        // TTL은 업로드 URL과 동일 설정을 재사용한다(기본 600초). 소유자 검증은 loadOwned가 이미 수행.
        String maskedFileUrl = result.getS3MaskedKey() == null ? null
                : s3PresignedUrlClient.issueDownloadUrl(result.getS3MaskedKey(),
                        Duration.ofSeconds(analysisProperties.uploadUrlExpiresSeconds()));
        return DocumentResultResponse.from(result, maskedFileUrl);
    }

    @Override
    public Page<DocumentSummaryResponse> list(String userPublicId, Pageable pageable) {
        Page<Document> documents = documentRepository.findAllByUserPublicId(userPublicId, pageable);
        List<Long> ids = documents.stream().map(Document::getId).toList();
        Map<Long, DocumentResult> resultBySubmissionId = ids.isEmpty() ? Map.of()
                : documentResultRepository.findAllBySubmission_IdIn(ids).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                r -> r.getSubmission().getId(), r -> r));
        return documents.map(d -> DocumentSummaryResponse.from(d, resultBySubmissionId.get(d.getId())));
    }

    @Override
    @Transactional
    public SubmissionResponse retry(String userPublicId, String publicId) {
        Document document = loadOwned(userPublicId, publicId);
        if (document.getStatus() != DocumentStatus.FAILED) {
            throw new BusinessException(CommonErrorCode.UNPROCESSABLE_ENTITY);
        }
        document.markAnalyzing();
        // S3 원본 재사용 — 최초 제출 때 사용한 동일 키로 Lambda A 재트리거.
        String s3Key = buildS3Key(document.getPublicId(), document.getFileName());
        analysisRequestPublisher.publishRetry(document.getPublicId(), userPublicId, s3Key);
        return SubmissionResponse.forRetry(document);
    }

    /** publicId로 문서를 조회 + 소유자 검증. 없으면 404, 다른 소유자면 403. */
    private Document loadOwned(String userPublicId, String publicId) {
        Document document = documentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(DocumentErrorCode.DOCUMENT_NOT_FOUND));
        if (!document.isOwnedBy(userPublicId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return document;
    }

    /**
     * S3 키 — 날짜 파티션 + publicId 디렉토리. 동일 파일명 충돌 회피 + Lambda 측 조회 편의.
     *
     * <p>접두사는 반드시 {@code original/}이어야 한다. Lambda A의 S3 ObjectCreated 트리거가
     * {@code original/} 접두사로 필터링돼 있어, 다른 접두사로 올리면 트리거가 걸리지 않는다(핸드오프 실패).
     */
    private String buildS3Key(String publicId, String fileName) {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return "original/%s/%s/%s".formatted(date, publicId, fileName);
    }

    /**
     * S3 오브젝트 메타데이터 — Lambda A가 결과 경로 분기에 사용한다.
     * 키 이름은 docs/document-analysis/ai-pipeline.md SSOT. 어긋나면 파이프라인이 깨진다.
     *
     * <p>키 네이밍은 언더스코어로 통일(account-a-contract-notice.md 결정 1). {@code analysis_document_type}은
     * Lambda B 입력으로 백엔드가 심기로 합의(동 §2, 결정 3) — 누락 시 Lambda는 UNKNOWN으로 처리.
     * {@code user_lang}은 추후 과제로 보류(현재 프론트 하드코딩) — Lambda의 "ko" 기본값이 안전망.
     */
    private Map<String, String> buildS3Metadata(Document document) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", analysisProperties.source());
        metadata.put("document_id", document.getPublicId());
        metadata.put("analysis_document_type", document.getAnalysisDocumentType().name());
        if (analysisProperties.isProductionSource()
                && analysisProperties.resultQueueArn() != null
                && !analysisProperties.resultQueueArn().isBlank()) {
            metadata.put("result_queue_arn", analysisProperties.resultQueueArn());
        }
        return metadata;
    }
}
