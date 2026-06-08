package com.gb.admin.domain.user.service;

import com.gb.admin.domain.user.dto.response.AdminMemberPageResponse;
import com.gb.admin.global.client.KycStatus;

public interface AdminUserManagementService {

    AdminMemberPageResponse search(String q, KycStatus kycStatus, int page, int size);

    void approveKyc(String userPublicId, String adminPublicId, String ipAddress);

    void rejectKyc(String userPublicId, String adminPublicId, String reason, String ipAddress);
}
