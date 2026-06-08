package com.gb.community.domain.admin.dto.response;

import com.gb.community.domain.post.entity.Post;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "관리자 신고 게시글 단건(/internal/admin)")
public record AdminReportView(
        String postPublicId,
        String postTitle,
        String authorPublicId,
        @Schema(description = "신고 카테고리. 본체에 신고 컬럼이 없어 발표용 프록시로 PostCategory를 노출한다(향후 reports 테이블 도입 시 교체).")
        String category,
        @Schema(description = "신고 수 — 본체에 reports 테이블 없어 발표용으로 comment_count 를 프록시로 노출.")
        long reportCount,
        @Schema(description = "마지막 신고 시각 — 발표용으로 게시글 updated_at 사용.")
        LocalDateTime lastReportedAt
) {

    public static AdminReportView from(Post p) {
        return new AdminReportView(
                p.getPublicId(),
                p.getTitle(),
                p.getUserPublicId(),
                p.getCategory() != null ? p.getCategory().name() : null,
                p.getCommentCount() != null ? p.getCommentCount() : 0,
                p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt()
        );
    }
}
