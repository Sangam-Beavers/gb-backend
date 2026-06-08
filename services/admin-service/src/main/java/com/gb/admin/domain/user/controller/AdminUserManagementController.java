package com.gb.admin.domain.user.controller;

import com.gb.admin.domain.user.dto.request.KycRejectRequest;
import com.gb.admin.domain.user.dto.response.AdminMemberPageResponse;
import com.gb.admin.domain.user.service.AdminUserManagementService;
import com.gb.admin.global.client.KycStatus;
import com.gb.admin.global.common.util.ClientIpResolver;
import com.gb.admin.global.security.CurrentAdminPublicId;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Users (Management)", description = "회원 검색/KYC 승인·거절")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserManagementController {

    private final AdminUserManagementService adminUserManagementService;

    @Operation(summary = "회원 검색",
            description = "이메일/이름/닉네임 부분 일치 검색 + KYC 상태 필터. Phase 1은 Mock fixture.")
    @GetMapping
    public ApiResponse<AdminMemberPageResponse> search(
            @Parameter(description = "검색어(이메일/이름/닉네임 부분 일치)") @RequestParam(required = false) String q,
            @Parameter(description = "KYC 상태") @RequestParam(required = false, name = "kyc_status") KycStatus kycStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminUserManagementService.search(q, kycStatus, page, size));
    }

    @Operation(summary = "KYC 승인",
            description = "회원의 KYC를 승인하고 audit_log를 남긴다. Phase 1은 Mock 시뮬레이션만.")
    @PostMapping("/{publicId}/kyc/approve")
    public ApiResponse<Void> approveKyc(
            @PathVariable String publicId,
            @CurrentAdminPublicId String adminPublicId,
            HttpServletRequest request) {
        adminUserManagementService.approveKyc(publicId, adminPublicId, ClientIpResolver.resolve(request));
        return ApiResponse.success(null);
    }

    @Operation(summary = "KYC 거절",
            description = "회원의 KYC를 거절하고 audit_log에 사유까지 저장한다.")
    @PostMapping("/{publicId}/kyc/reject")
    public ApiResponse<Void> rejectKyc(
            @PathVariable String publicId,
            @Valid @RequestBody KycRejectRequest request,
            @CurrentAdminPublicId String adminPublicId,
            HttpServletRequest httpRequest) {
        adminUserManagementService.rejectKyc(publicId, adminPublicId, request.reason(),
                ClientIpResolver.resolve(httpRequest));
        return ApiResponse.success(null);
    }
}
