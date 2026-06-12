package com.gb.community.domain.report.service;

import com.gb.community.domain.report.dto.request.CreateReportRequest;
import com.gb.community.domain.report.dto.response.ReportResponse;

public interface ReportService {

    /** 게시글 신고. returns 201 응답용 DTO. */
    ReportResponse reportPost(String reporterPublicId, String postPublicId,
                               CreateReportRequest request);

    /** 댓글 신고. */
    ReportResponse reportComment(String reporterPublicId, String postPublicId,
                                  String commentPublicId, CreateReportRequest request);
}
