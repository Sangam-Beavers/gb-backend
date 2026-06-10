package com.gb.community.domain.admin.dto.response;

import java.util.List;

/** 관리자 회원 활동 조회 응답 — 게시글 + 댓글 목록. */
public record AdminUserActivityResponse(
        List<AdminUserPostView> posts,
        int postPage,
        int postSize,
        long postTotal,
        List<AdminUserCommentView> comments,
        int commentPage,
        int commentSize,
        long commentTotal
) {}
