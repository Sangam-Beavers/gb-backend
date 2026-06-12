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
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import com.gb.document.domain.document.service.DocumentSubmissionService;
import com.gb.document.global.client.s3.S3PresignedUrlClient;
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
    private final AnalysisProperties analysisProperties;

    @Override
    @Transactional
    public SubmissionResponse submit(String userPublicId, SubmitRequest request) {
        String publicId = UUID.randomUUID().toString();
        // 발급 시점에 키를 확정해 엔티티에 저장 — 어떤 키로 업로드를 받았는지 기록(추적·디버깅).
        String s3Key = buildS3Key(publicId, request.fileName());
        Document document = Document.builder()
                .publicId(publicId)
                .userPublicId(userPublicId)
                .analysisDocumentType(request.analysisDocumentType())
                .fileName(request.fileName())
                .status(DocumentStatus.ANALYZING)
                .s3Key(s3Key)
                .build();
        documentRepository.save(document);

        Map<String, String> metadata = buildS3Metadata(document);
        Duration ttl = Duration.ofSeconds(analysisProperties.uploadUrlExpiresSeconds());

        S3PresignedUrlClient.IssueUrlResult issued = s3PresignedUrlClient.issueUploadUrl(
                s3Key, DEFAULT_CONTENT_TYPE, metadata, ttl);

        return SubmissionResponse.forSubmit(
                document, issued.url(), issued.signedHeaders(), issued.expiresAt());
    }

    @Override
    @Transactional
    public DocumentStatusResponse getStatus(String userPublicId, String publicId) {
        Document document = loadOwned(userPublicId, publicId);
        syncStatusFromResultIfArrived(document);
        return DocumentStatusResponse.from(document);
    }

    /**
     * status가 ANALYZING인데 분석 결과가 이미 도착해 있으면 결과 기준으로 동기화한다(lazy-sync).
     *
     * <p>운영(production)은 SQS Consumer({@link AnalysisResultIngestServiceImpl})가 결과 저장과
     * status 갱신을 함께 수행하지만, 개발(development) 경로는 Lambda B가 온프렘 MySQL에
     * document_results만 직접 INSERT해 status가 ANALYZING으로 남을 수 있다(프론트 폴링이 끝나지
     * 않는 원인 — 2026-06-05 실측). Lambda B에도 status 갱신을 추가했으나, 배포 시점 차이·운영
     * Consumer 지연/유실에 대한 안전망으로 폴링 시점에 한 번 더 동기화한다.
     * 매핑은 Consumer와 동일: FAILED→FAILED, COMPLETED/PARTIAL→COMPLETED.
     */
    private void syncStatusFromResultIfArrived(Document document) {
        if (document.getStatus() != DocumentStatus.ANALYZING) {
            return;
        }
        documentResultRepository.findBySubmission_PublicId(document.getPublicId())
                .ifPresent(result -> {
                    if (result.getProcessingStatus() == ProcessingStatus.FAILED) {
                        document.markFailed();
                    } else {
                        document.markCompleted();
                    }
                });
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
    public Page<DocumentSummaryResponse> list(String userPublicId, List<String> statuses, Pageable pageable) {
        Page<Document> documents = (statuses == null || statuses.isEmpty())
                ? documentRepository.findAllByUserPublicId(userPublicId, pageable)
                : documentRepository.findAllByUserPublicIdAndStatusIn(
                        userPublicId, toStatusEnums(statuses), pageable);
        List<Long> ids = documents.stream().map(Document::getId).toList();
        Map<Long, DocumentResult> resultBySubmissionId = ids.isEmpty() ? Map.of()
                : documentResultRepository.findAllBySubmission_IdIn(ids).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                r -> r.getSubmission().getId(), r -> r));
        return documents.map(d -> DocumentSummaryResponse.from(d, resultBySubmissionId.get(d.getId())));
    }

    /**
     * 목록 status 필터 문자열 → enum 변환. enum 검증은 @Pattern이 아닌 Service에서 한다(CLAUDE §6).
     * 잘못된 값(오타 등)은 COMMON4001 — 필터 입력값 오류라 도메인 코드 신설 없이 공통 검증 코드 사용.
     */
    private List<DocumentStatus> toStatusEnums(List<String> statuses) {
        try {
            return statuses.stream().map(DocumentStatus::valueOf).toList();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
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
