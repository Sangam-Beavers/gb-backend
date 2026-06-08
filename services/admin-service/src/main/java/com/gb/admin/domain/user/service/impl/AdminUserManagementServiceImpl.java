package com.gb.admin.domain.user.service.impl;

import com.gb.admin.domain.auditLog.service.AuditLogService;
import com.gb.admin.domain.user.dto.response.AdminMemberPageResponse;
import com.gb.admin.domain.user.dto.response.AdminMemberResponse;
import com.gb.admin.domain.user.service.AdminUserManagementService;
import com.gb.admin.global.client.AdminMemberSummary;
import com.gb.admin.global.client.KycStatus;
import com.gb.admin.global.client.MemberAdminClient;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserManagementServiceImpl implements AdminUserManagementService {

    private final MemberAdminClient memberAdminClient;
    private final AuditLogService auditLogService;

    @Override
    public AdminMemberPageResponse search(String q, KycStatus kycStatus, int page, int size) {
        Page<AdminMemberSummary> result = memberAdminClient.search(q, kycStatus, page, size);
        return AdminMemberPageResponse.from(result.map(AdminMemberResponse::from));
    }

    @Override
    @Transactional
    public void approveKyc(String userPublicId, String adminPublicId, String ipAddress) {
        // member-service 호출 → audit_log INSERT 순서. Phase 1은 Mock이라 실제 status 변경 없음.
        memberAdminClient.approveKyc(userPublicId, adminPublicId);
        auditLogService.record(
                adminPublicId, "KYC_APPROVE", "MEMBER", userPublicId,
                ipAddress,
                "{\"kyc_status\":\"PENDING\"}",
                "{\"kyc_status\":\"APPROVED\"}"
        );
    }

    @Override
    @Transactional
    public void rejectKyc(String userPublicId, String adminPublicId, String reason, String ipAddress) {
        memberAdminClient.rejectKyc(userPublicId, adminPublicId, reason);
        // before/after 모두 raw JSON 문자열로 남긴다 — 검색 용도가 아니라 사후 추적용.
        String after = "{\"kyc_status\":\"REJECTED\",\"reason\":" + jsonString(reason) + "}";
        auditLogService.record(
                adminPublicId, "KYC_REJECT", "MEMBER", userPublicId,
                ipAddress,
                "{\"kyc_status\":\"PENDING\"}",
                after
        );
    }

    /** JSON 문자열로 안전하게 박는 최소 escape — 라이브러리 없이 직접(audit raw 용). */
    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
