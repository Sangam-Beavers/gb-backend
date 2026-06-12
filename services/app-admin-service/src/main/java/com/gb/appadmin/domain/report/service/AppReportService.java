package com.gb.appadmin.domain.report.service;

import com.gb.appadmin.domain.report.dto.response.MemberReportsResponse;
import com.gb.appadmin.domain.report.dto.response.ReportedAuthorPageResponse;

public interface AppReportService {

    /**
     * 신고당한 회원 목록 (신고수 정렬) + member 표시정보 enrich.
     */
    ReportedAuthorPageResponse getReportedAuthors(int page, int size);

    /**
     * 특정 회원이 받은 신고 상세 목록 + member 표시정보 enrich.
     */
    MemberReportsResponse getMemberReports(String userPublicId);
}
