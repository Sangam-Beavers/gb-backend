package com.gb.community.domain.admin.dto.response;

import com.gb.community.domain.comment.entity.Comment;
import java.time.LocalDateTime;

/** 관리자 회원 활동 조회 — 댓글 단건 뷰. */
public record AdminUserCommentView(
        String publicId,
        String postPublicId,
        String content,
        int likeCount,
        LocalDateTime createdAt
) {
    public static AdminUserCommentView from(Comment c) {
        return new AdminUserCommentView(
                c.getPublicId(),
                c.getPost() != null ? c.getPost().getPublicId() : null,
                c.getContent(),
                c.getLikeCount(),
                c.getCreatedAt()
        );
    }
}
