package com.gb.community.domain.admin.service;

import com.gb.community.domain.admin.dto.response.AdminReportPageResponse;
import com.gb.community.domain.admin.dto.response.AdminUserActivityResponse;
import com.gb.community.domain.admin.dto.response.ReportStatsResponse;

public interface CommunityAdminInternalService {

    AdminReportPageResponse reports(String category, int page, int size);

    void hidePost(String publicId);

    void deletePost(String publicId);

    ReportStatsResponse stats();

    AdminUserActivityResponse getUserActivity(String userPublicId, int postPage, int commentPage, int size);
}
