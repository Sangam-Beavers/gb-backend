package com.gb.admin.domain.community.service;

import com.gb.admin.domain.community.dto.response.AdminReportPageResponse;

public interface AdminCommunityService {

    AdminReportPageResponse reports(String category, int page, int size);

    void hidePost(String postPublicId, String adminPublicId, String ipAddress);

    void deletePost(String postPublicId, String adminPublicId, String ipAddress);
}
