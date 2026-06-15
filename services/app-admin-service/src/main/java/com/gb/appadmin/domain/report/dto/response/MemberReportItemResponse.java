package com.gb.appadmin.domain.report.dto.response;

/**
 * community AdminReportView 1:1 매핑 — 개별 신고 항목.
 * category 필드명은 community가 reason을 category로 노출하는 규약을 따른다.
 */
public record MemberReportItemResponse(
        String postPublicId,
        String postTitle,
        String authorPublicId,
        String targetType,
        String category,
        long reportCount,
        String status,
        String lastReportedAt
) {}
