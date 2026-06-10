package com.gb.appadmin.domain.member.dto.response;

import java.util.List;

/** 관리자 회원 상세 — 커뮤니티 활동(글+댓글) 응답. */
public record UserActivityResponse(
        List<UserPostView> posts,
        int postPage,
        int postSize,
        long postTotal,
        List<UserCommentView> comments,
        int commentPage,
        int commentSize,
        long commentTotal
) {}
