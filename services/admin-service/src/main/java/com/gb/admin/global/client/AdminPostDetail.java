package com.gb.admin.global.client;

import java.time.LocalDateTime;

/** community-service 게시글 단건 본문 조회 결과(신고 처리 "보기"용). */
public record AdminPostDetail(
        String postPublicId,
        String userPublicId,
        String title,
        String content,
        String language,
        LocalDateTime createdAt
) {
}
