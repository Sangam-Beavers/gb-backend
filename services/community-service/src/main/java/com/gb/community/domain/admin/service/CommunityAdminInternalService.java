package com.gb.community.domain.admin.service;

import com.gb.community.domain.admin.dto.response.AdminReportPageResponse;
import com.gb.community.domain.admin.dto.response.AdminReportedAuthorDetailResponse;
import com.gb.community.domain.admin.dto.response.AdminReportedAuthorView;
import com.gb.community.domain.admin.dto.response.AdminUserActivityResponse;
import com.gb.community.domain.admin.dto.response.ReportStatsResponse;
import java.util.List;

public interface CommunityAdminInternalService {

    /** 신고 콘텐츠 목록 (실제 reports 집계, 기존 mock 교체). 필터: status, reason. */
    AdminReportPageResponse reports(String status, String reason, int page, int size);

    /** 게시글 숨김 → soft-delete + 연관 reports RESOLVED_DELETED 처리. */
    void hidePost(String publicId);

    /** 게시글 삭제 → soft-delete + 연관 reports RESOLVED_DELETED 처리. */
    void deletePost(String publicId);

    /** 댓글 삭제 → soft-delete + 연관 reports RESOLVED_DELETED 처리 (신규). */
    void deleteComment(String commentPublicId);

    /** 신고 통계 — 실제 PENDING 카운트 반환 (기존 mock 교체). */
    ReportStatsResponse stats();

    /** 신고당한 작성자 집계 목록 (신규). */
    List<AdminReportedAuthorView> reportedAuthors();

    /** 특정 작성자가 받은 신고 상세 (신규). */
    AdminReportedAuthorDetailResponse reportedAuthorDetail(String authorPublicId);

    AdminUserActivityResponse getUserActivity(String userPublicId, int postPage, int commentPage, int size);
}
