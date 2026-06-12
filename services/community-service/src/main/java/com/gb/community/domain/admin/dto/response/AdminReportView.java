package com.gb.community.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 관리자 신고 콘텐츠 단건 뷰 — 실제 reports 집계 기반.
 *
 * <p>필드명은 하위 호환 유지 (admin-service / app-admin-service snake_case 응답 기준):
 * post_public_id, post_title, author_public_id, category, report_count, last_reported_at.
 * {@code category} 필드는 이제 PostCategory가 아닌 ReportReason(SPAM/ABUSE/FRAUD/SEXUAL/ETC)을 담는다.
 *
 * <p>targetType(POST/COMMENT) 필드를 추가해 다운스트림이 댓글 신고도 구분할 수 있게 한다.
 */
@Schema(description = "관리자 신고 콘텐츠 단건(/internal/admin/reports)")
public record AdminReportView(

        @Schema(description = "콘텐츠 publicId (게시글 또는 댓글)")
        String postPublicId,

        @Schema(description = "콘텐츠 제목 또는 내용 요약 (게시글=제목, 댓글=내용 앞 100자)")
        String postTitle,

        @Schema(description = "작성자 publicId")
        String authorPublicId,

        @Schema(description = "신고 대상 유형 (POST / COMMENT)")
        String targetType,

        @Schema(description = "대표 신고 사유 (최다 빈도) — SPAM/ABUSE/FRAUD/SEXUAL/ETC")
        String category,

        @Schema(description = "신고 수 (GROUP BY target 집계)")
        long reportCount,

        @Schema(description = "처리 상태 (PENDING / RESOLVED_DELETED / DISMISSED)")
        String status,

        @Schema(description = "마지막 신고 시각")
        LocalDateTime lastReportedAt
) {
}
