package com.gb.community.domain.report.entity;

import com.gb.community.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 신고 엔티티.
 *
 * <p>게시글({@link ReportTargetType#POST}) 또는 댓글({@link ReportTargetType#COMMENT})에 대한 신고를 저장한다.
 *
 * <p>유니크 제약 {@code (reporter_public_id, target_type, target_id)}: 1인 1대상 1회 신고.
 * 위반 시 {@code CommunityErrorCode.DUPLICATE_REPORT}로 처리.
 *
 * <p>targetId: 같은 community 스키마 내부 참조(post.id / comment.id)이므로 BIGINT 허용(CLAUDE.md §4).
 * {@code @ManyToOne} 대신 원시 Long으로 두는 이유 — POST와 COMMENT가 같은 targetId 컬럼을 공유하는
 * polymorphic 구조라 단일 FK 매핑이 부적절하다.
 */
@Entity
@Getter
@Table(
        name = "reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_report_reporter_target",
                columnNames = {"reporter_public_id", "target_type", "target_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자 (UUID). */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /** 신고 대상 유형 (POST / COMMENT). */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20, nullable = false)
    private ReportTargetType targetType;

    /** 신고 대상의 내부 PK (post.id 또는 comment.id). */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 신고한 사람 (member-service users.public_id 논리 참조 — MSA 경계). */
    @Column(name = "reporter_public_id", length = 36, nullable = false)
    private String reporterPublicId;

    /** 신고 사유. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 20, nullable = false)
    private ReportReason reason;

    /** 신고 상세 설명 (선택 입력). */
    @Column(name = "detail", length = 500)
    private String detail;

    /** 처리 상태. 기본값 PENDING. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private ReportStatus status;

    @Builder
    private Report(String publicId, ReportTargetType targetType, Long targetId,
                   String reporterPublicId, ReportReason reason, String detail) {
        this.publicId = publicId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reporterPublicId = reporterPublicId;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.PENDING;
    }

    /**
     * 신고 생성 정적 팩토리 (CLAUDE.md §4 — Entity.of 패턴).
     * publicId(UUID)는 서버가 생성한다. 상태는 항상 PENDING으로 시작.
     */
    public static Report of(String reporterPublicId, ReportTargetType targetType, Long targetId,
                             ReportReason reason, String detail) {
        return Report.builder()
                .publicId(UUID.randomUUID().toString())
                .reporterPublicId(reporterPublicId)
                .targetType(targetType)
                .targetId(targetId)
                .reason(reason)
                .detail(detail)
                .build();
    }

    /** 콘텐츠 삭제로 처리 완료 처리. */
    public void resolve() {
        this.status = ReportStatus.RESOLVED_DELETED;
    }

    /** 관리자 무효 처리. */
    public void dismiss() {
        this.status = ReportStatus.DISMISSED;
    }
}
