package com.gb.document.domain.document.entity;

import com.gb.document.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 분석 결과(상세). SQS Consumer가 받은 result JSON v1.1을 그대로 매핑한다.
 *
 * <p>테이블 {@code document_results}, submission_id로 {@link Document}와 1:1.
 * CLAUDE.md §4 — 같은 스키마 내부 참조이므로 JPA @OneToOne 매핑 허용.
 *
 * <p>wage_summary / risk_items는 MySQL JSON 컬럼 + Hibernate 6 {@link SqlTypes#JSON} 매핑으로
 * 객체 ↔ JSON 양방향 처리. 별도 컨버터 없이 record(`WageSummary`, `RiskItem`)를 그대로 사용한다.
 */
@Entity
@Getter
@Table(name = "document_results")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentResult extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 1:1로 묶인 분석 요청. UNIQUE 제약으로 한 submission당 결과 1건. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false, unique = true)
    private Document submission;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_document_type", length = 30, nullable = false)
    private AnalysisDocumentType analysisDocumentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", length = 20, nullable = false)
    private ProcessingStatus processingStatus;

    /** v1.1: NONE 제거 → null 허용("위험도 미평가"). */
    @Enumerated(EnumType.STRING)
    @Column(name = "overall_risk_level", length = 10)
    private RiskLevel overallRiskLevel;

    /** v1.1: DECIMAL(3,2), 범위 [0.00, 1.00]. 표시 전용 수치라 number로 응답. */
    @Column(name = "ocr_confidence", precision = 3, scale = 2)
    private BigDecimal ocrConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "wage_summary", columnDefinition = "JSON")
    private WageSummary wageSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_items", columnDefinition = "JSON")
    private List<RiskItem> riskItems;

    @Column(name = "translated_text", columnDefinition = "TEXT")
    private String translatedText;

    @Column(name = "translated_lang", length = 8)
    private String translatedLang;

    /** 풀 URL("s3://bucket/key") 저장 — 환경별 버킷 구분 보존(api-spec.md §3 v1.1). */
    @Column(name = "masked_file_url", length = 512)
    private String maskedFileUrl;

    @Column(name = "failed_reason", length = 255)
    private String failedReason;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Builder
    private DocumentResult(Document submission,
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
                           LocalDateTime completedAt) {
        this.submission = submission;
        this.analysisDocumentType = analysisDocumentType;
        this.processingStatus = processingStatus;
        this.overallRiskLevel = overallRiskLevel;
        this.ocrConfidence = ocrConfidence;
        this.wageSummary = wageSummary;
        this.riskItems = riskItems;
        this.translatedText = translatedText;
        this.translatedLang = translatedLang;
        this.maskedFileUrl = maskedFileUrl;
        this.failedReason = failedReason;
        this.completedAt = completedAt;
    }

    /**
     * Consumer가 동일 submission의 결과를 재수신했을 때 호출 — at-least-once 멱등 처리, retry 성공 시
     * 갱신용. submission 1:1 관계는 불변, 나머지 필드만 교체. {@code @Setter} 금지 규약(CLAUDE.md §4)을
     * 지키기 위한 도메인 메서드 형태로 제공한다.
     */
    public void applyAnalysisResult(AnalysisDocumentType analysisDocumentType,
                                    ProcessingStatus processingStatus,
                                    RiskLevel overallRiskLevel,
                                    BigDecimal ocrConfidence,
                                    WageSummary wageSummary,
                                    List<RiskItem> riskItems,
                                    String translatedText,
                                    String translatedLang,
                                    String maskedFileUrl,
                                    String failedReason,
                                    LocalDateTime completedAt) {
        this.analysisDocumentType = analysisDocumentType;
        this.processingStatus = processingStatus;
        this.overallRiskLevel = overallRiskLevel;
        this.ocrConfidence = ocrConfidence;
        this.wageSummary = wageSummary;
        this.riskItems = riskItems;
        this.translatedText = translatedText;
        this.translatedLang = translatedLang;
        this.maskedFileUrl = maskedFileUrl;
        this.failedReason = failedReason;
        this.completedAt = completedAt;
    }
}
