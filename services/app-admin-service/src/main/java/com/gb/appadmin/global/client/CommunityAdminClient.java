package com.gb.appadmin.global.client;

import com.gb.appadmin.domain.member.dto.response.UserActivityResponse;
import com.gb.appadmin.domain.report.dto.response.MemberReportItemResponse;
import com.gb.appadmin.domain.report.dto.response.ReportedAuthorSummary;
import java.util.List;

public interface CommunityAdminClient {

    UserActivityResponse getUserActivity(String userPublicId, int postPage, int commentPage, int size);

    /**
     * community /internal/admin/reports/by-author 호출.
     * 신고당한 작성자 집계 목록 (authorPublicId, totalReportCount, reportedContentCount).
     */
    List<ReportedAuthorSummary> getReportedAuthors(int page, int size);

    /**
     * community /internal/admin/reports/by-author/{authorPublicId} 호출.
     * 특정 작성자가 받은 신고 항목 목록 (AdminReportView 리스트).
     */
    List<MemberReportItemResponse> getMemberReports(String authorPublicId);
}
