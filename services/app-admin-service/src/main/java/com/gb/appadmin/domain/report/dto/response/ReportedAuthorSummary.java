package com.gb.appadmin.domain.report.dto.response;

/**
 * 신고당한 회원 집계 요약 — community by-author + member 표시정보 합산.
 */
public record ReportedAuthorSummary(
        String authorPublicId,
        String name,
        String nickname,
        long totalReportCount,
        long reportedContentCount
) {}
