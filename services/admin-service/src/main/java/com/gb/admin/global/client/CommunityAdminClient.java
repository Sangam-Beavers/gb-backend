package com.gb.admin.global.client;

import org.springframework.data.domain.Page;

public interface CommunityAdminClient {

    Page<AdminReportSummary> reports(String status, String reason, int page, int size);

    void hidePost(String postPublicId, String adminPublicId);

    void deletePost(String postPublicId, String adminPublicId);

    /** 신고 거부(기각) — 게시글 유지, 연관 신고만 DISMISSED. */
    void dismissReports(String postPublicId, String adminPublicId);

    /** 게시글 단건 본문 조회 (신고 처리 "보기"용). */
    AdminPostDetail postDetail(String postPublicId);

    long pendingReportCount();
}
