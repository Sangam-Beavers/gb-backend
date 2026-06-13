package com.gb.community.domain.admin.dto.response;

import com.gb.community.domain.post.entity.Post;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * [Internal] 관리자 게시글 단건 본문 조회 응답 — 신고 처리 화면 "보기"에서 본문 확인용.
 */
@Schema(description = "관리자 게시글 단건(본문 포함)")
public record AdminPostDetailResponse(
        String postPublicId,
        String userPublicId,
        String title,
        String content,
        String language,
        LocalDateTime createdAt
) {
    public static AdminPostDetailResponse from(Post post) {
        return new AdminPostDetailResponse(
                post.getPublicId(),
                post.getUserPublicId(),
                post.getTitle(),
                post.getContent(),
                post.getLanguage(),
                post.getCreatedAt()
        );
    }
}
