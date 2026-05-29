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
        Map<String, String> metadata = buildS3Metadata(publicId);
        Duration ttl = Duration.ofSeconds(analysisProperties.uploadUrlExpiresSeconds());

        S3PresignedUrlClient.IssueUrlResult issued = s3PresignedUrlClient.issueUploadUrl(
                s3Key, DEFAULT_CONTENT_TYPE, metadata, ttl);

        return SubmissionResponse.forSubmit(document, issued.url(), issued.expiresAt());
    }

    @Override
    public DocumentStatusResponse getStatus(String userPublicId, String publicId) {
        Document document = loadOwned(userPublicId, publicId);
        return DocumentStatusResponse.from(document);
    }

    @Override
    public DocumentResultResponse getResult(String userPublicId, String publicId) {
        Document document = loadOwned(userPublicId, publicId);
        DocumentResult result = documentResultRepository.findBySubmission_PublicId(publicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNPROCESSABLE_ENTITY));
        // 권한 검증은 submission으로 이미 끝났지만, lazy proxy로 result.submission 접근을 피하려 document만 사용.
        if (!document.isOwnedBy(userPublicId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return DocumentResultResponse.from(result);
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

    /** S3 키 — 날짜 파티션 + publicId 디렉토리. 동일 파일명 충돌 회피 + Lambda 측 조회 편의. */
    private String buildS3Key(String publicId, String fileName) {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return "uploads/%s/%s/%s".formatted(date, publicId, fileName);
    }

    /**
     * S3 오브젝트 메타데이터 — Lambda A가 결과 경로 분기에 사용한다.
     * 키 이름은 docs/document-analysis/ai-pipeline.md SSOT. 어긋나면 파이프라인이 깨진다.
     */
    private Map<String, String> buildS3Metadata(String publicId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", analysisProperties.source());
        metadata.put("document_id", publicId);
        if (analysisProperties.isProductionSource()
                && analysisProperties.resultQueueArn() != null
                && !analysisProperties.resultQueueArn().isBlank()) {
            metadata.put("result_queue_arn", analysisProperties.resultQueueArn());
        }
        return metadata;
    }
}
