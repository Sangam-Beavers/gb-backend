package com.gb.appadmin.domain.member.dto.response;

/** 관리자 회원 상세 — 게시글 단건 뷰. */
public record UserPostView(
        String publicId,
        String category,
        String title,
        int commentCount,
        int likeCount,
        String createdAt
) {}
