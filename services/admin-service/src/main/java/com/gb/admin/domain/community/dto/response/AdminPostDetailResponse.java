package com.gb.admin.domain.community.dto.response;

import com.gb.admin.global.client.AdminPostDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 신고 처리 "보기" — 게시글 본문 포함 단건 응답. */
@Schema(description = "관리자 게시글 단건(본문 포함)")
public record AdminPostDetailResponse(
        String postPublicId,
        String userPublicId,
        String title,
        String content,
        String language,
        LocalDateTime createdAt
) {
    public static AdminPostDetailResponse from(AdminPostDetail d) {
        return new AdminPostDetailResponse(
                d.postPublicId(), d.userPublicId(), d.title(),
                d.content(), d.language(), d.createdAt());
    }
}
