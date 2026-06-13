package com.gb.admin.domain.community.service.impl;

import com.gb.admin.domain.auditLog.service.AuditLogService;
import com.gb.admin.domain.community.dto.response.AdminPostDetailResponse;
import com.gb.admin.domain.community.dto.response.AdminReportPageResponse;
import com.gb.admin.domain.community.dto.response.AdminReportResponse;
import com.gb.admin.domain.community.service.AdminCommunityService;
import com.gb.admin.global.client.AdminReportSummary;
import com.gb.admin.global.client.CommunityAdminClient;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCommunityServiceImpl implements AdminCommunityService {

    private final CommunityAdminClient communityAdminClient;
    private final AuditLogService auditLogService;

    @Override
    public AdminReportPageResponse reports(String status, String reason, int page, int size) {
        Page<AdminReportSummary> result = communityAdminClient.reports(status, reason, page, size);
        return AdminReportPageResponse.from(result.map(AdminReportResponse::from));
    }

    @Override
    @Transactional
    public void hidePost(String postPublicId, String adminPublicId, String ipAddress) {
        communityAdminClient.hidePost(postPublicId, adminPublicId);
        auditLogService.record(
                adminPublicId, "POST_HIDE", "POST", postPublicId,
                ipAddress,
                "{\"visibility\":\"VISIBLE\"}",
                "{\"visibility\":\"HIDDEN\"}"
        );
    }

    @Override
    @Transactional
    public void deletePost(String postPublicId, String adminPublicId, String ipAddress) {
        communityAdminClient.deletePost(postPublicId, adminPublicId);
        auditLogService.record(
                adminPublicId, "POST_DELETE", "POST", postPublicId,
                ipAddress,
                "{\"visibility\":\"VISIBLE\"}",
                "{\"visibility\":\"DELETED\"}"
        );
    }

    @Override
    public AdminPostDetailResponse postDetail(String postPublicId) {
        return AdminPostDetailResponse.from(communityAdminClient.postDetail(postPublicId));
    }

    @Override
    @Transactional
    public void dismissReport(String postPublicId, String adminPublicId, String ipAddress) {
        // 신고 거부(기각): 게시글은 유지, 연관 신고만 DISMISSED. 운영자 행동은 audit_log에 남긴다.
        communityAdminClient.dismissReports(postPublicId, adminPublicId);
        auditLogService.record(
                adminPublicId, "REPORT_DISMISS", "POST", postPublicId,
                ipAddress,
                "{\"reportStatus\":\"PENDING\"}",
                "{\"reportStatus\":\"DISMISSED\"}"
        );
    }
}
