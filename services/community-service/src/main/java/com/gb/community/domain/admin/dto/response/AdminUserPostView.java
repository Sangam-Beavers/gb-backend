package com.gb.community.domain.admin.dto.response;

import com.gb.community.domain.post.entity.Post;
import java.time.LocalDateTime;

/** 관리자 회원 활동 조회 — 게시글 단건 뷰. */
public record AdminUserPostView(
        String publicId,
        String category,
        String title,
        int commentCount,
        int likeCount,
        LocalDateTime createdAt
) {
    public static AdminUserPostView from(Post p) {
        return new AdminUserPostView(
                p.getPublicId(),
                p.getCategory() != null ? p.getCategory().name() : null,
                p.getTitle(),
                p.getCommentCount(),
                p.getLikeCount(),
                p.getCreatedAt()
        );
    }
}
