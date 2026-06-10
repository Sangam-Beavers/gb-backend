package com.gb.appadmin.domain.member.dto.response;

/** 관리자 회원 상세 — 댓글 단건 뷰. */
public record UserCommentView(
        String publicId,
        String postPublicId,
        String content,
        int likeCount,
        String createdAt
) {}
