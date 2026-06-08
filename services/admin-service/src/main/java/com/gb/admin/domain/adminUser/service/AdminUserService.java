package com.gb.admin.domain.adminUser.service;

import com.gb.admin.domain.adminUser.dto.response.AdminUserResponse;

public interface AdminUserService {

    /**
     * JWT의 {@code public_id} claim에 해당하는 관리자 정보를 반환한다.
     * Phase 1은 DB 미스 시 mock(admin01 SUPER)을 반환해 발표 데모를 막지 않는다(CLAUDE §12 추측 금지 —
     * 명세에 적힌 "못 찾으면 mock 반환" 정책 그대로).
     */
    AdminUserResponse getMe(String adminPublicId);
}
