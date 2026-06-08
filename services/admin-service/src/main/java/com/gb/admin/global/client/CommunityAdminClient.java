package com.gb.admin.global.client;

import org.springframework.data.domain.Page;

public interface CommunityAdminClient {

    Page<AdminReportSummary> reports(String category, int page, int size);

    void hidePost(String postPublicId, String adminPublicId);

    void deletePost(String postPublicId, String adminPublicId);

    long pendingReportCount();
}
