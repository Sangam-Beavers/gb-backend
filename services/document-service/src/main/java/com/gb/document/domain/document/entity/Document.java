package com.gb.document.domain.document.entity;

import com.gb.document.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 분석 대상 문서(=업로드된 계약서 등)의 메타 엔티티.
 *
 * <p>여기는 <b>이유진 영역</b>(분석 파이프라인)이며, 챗봇 컨트롤러(심규보)는 권한 검증을 위해
 * {@link com.gb.document.domain.document.repository.DocumentRepository#findByPublicId(String)} 한
 * 메서드만 사용한다. AI-WORK-SPLIT.md §3-③의 공유 인터페이스를 깨지 않는 범위에서 필드를 확장한다.
 *
 * <p>테이블은 {@code document_submissions} — api-spec.md §2 (v1.1) SSOT.
 */
@Entity
@Getter
@Table(name = "document_submissions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자(UUID). conventions §0 — 내부 id는 응답/URL에 노출 금지. */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /**
     * 문서 소유자(member-service users.public_id 논리 참조).
     * CLAUDE.md §7 — MSA 경계를 넘는 회원 참조는 user_public_id(UUID)로, 물리 FK 금지.
     */
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    /** 분석 대상 문서 종류. v1.1에서 도입(도메인별 결과 필드를 강제 분리). */
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_document_type", length = 30, nullable = false)
    private AnalysisDocumentType analysisDocumentType;

    /** 사용자 업로드 시 원본 파일명. S3 키 구성 + 결과 화면 표시에 사용. */
    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    /** 분석 진행 상태. api-spec.md §2 SSOT — ANALYZING/COMPLETED/FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DocumentStatus status;

    @Builder
    private Document(String publicId,
                     String userPublicId,
                     AnalysisDocumentType analysisDocumentType,
                     String fileName,
                     DocumentStatus status) {
        this.publicId = publicId;
        this.userPublicId = userPublicId;
        this.analysisDocumentType = analysisDocumentType;
        this.fileName = fileName;
        this.status = status != null ? status : DocumentStatus.ANALYZING;
    }

    /** SQS Consumer가 분석 성공 결과를 받았을 때 호출. */
    public void markCompleted() {
        this.status = DocumentStatus.COMPLETED;
    }

    /** SQS Consumer가 분석 실패 결과를 받았을 때 호출. */
    public void markFailed() {
        this.status = DocumentStatus.FAILED;
    }

    /** retry 요청 시 호출 — FAILED 상태에서 ANALYZING으로 되돌린다. */
    public void markAnalyzing() {
        this.status = DocumentStatus.ANALYZING;
    }

    /** 요청자(userPublicId)가 문서 소유자가 맞는지 확인. */
    public boolean isOwnedBy(String userPublicId) {
        return this.userPublicId != null && this.userPublicId.equals(userPublicId);
    }
}
