package com.gb.admin.domain.community.controller;

import com.gb.admin.domain.community.dto.response.AdminReportPageResponse;
import com.gb.admin.domain.community.service.AdminCommunityService;
import com.gb.admin.global.common.util.ClientIpResolver;
import com.gb.admin.global.security.CurrentAdminPublicId;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Community", description = "커뮤니티 신고 처리")
@RestController
@RequestMapping("/api/v1/admin/community")
@RequiredArgsConstructor
public class AdminCommunityController {

    private final AdminCommunityService adminCommunityService;

    @Operation(summary = "신고 콘텐츠 목록",
            description = "status(PENDING/RESOLVED_DELETED/DISMISSED) 및 reason(SPAM/ABUSE/FRAUD/SEXUAL/ETC) 필터. 미지정 시 전체.")
    @GetMapping("/reports")
    public ApiResponse<AdminReportPageResponse> reports(
            @Parameter(description = "신고 처리 상태") @RequestParam(required = false) String status,
            @Parameter(description = "신고 사유") @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminCommunityService.reports(status, reason, page, size));
    }

    @Operation(summary = "게시글 숨김",
            description = "신고 누적 게시글을 숨김 처리(소프트 삭제)하고 audit_log를 남긴다.")
    @PostMapping("/posts/{publicId}/hide")
    public ApiResponse<Void> hide(
            @PathVariable String publicId,
            @CurrentAdminPublicId String adminPublicId,
            HttpServletRequest request) {
        adminCommunityService.hidePost(publicId, adminPublicId, ClientIpResolver.resolve(request));
        return ApiResponse.success(null);
    }

    @Operation(summary = "게시글 삭제",
            description = "신고 누적 게시글을 삭제하고 audit_log를 남긴다(REST DELETE).")
    @DeleteMapping("/posts/{publicId}")
    public ApiResponse<Void> delete(
            @PathVariable String publicId,
            @CurrentAdminPublicId String adminPublicId,
            HttpServletRequest request) {
        adminCommunityService.deletePost(publicId, adminPublicId, ClientIpResolver.resolve(request));
        return ApiResponse.success(null);
    }

    @Operation(summary = "신고 거부(기각)",
            description = "게시글은 유지하고 연관 신고만 DISMISSED 처리 + audit_log를 남긴다.")
    @PostMapping("/posts/{publicId}/dismiss")
    public ApiResponse<Void> dismiss(
            @PathVariable String publicId,
            @CurrentAdminPublicId String adminPublicId,
            HttpServletRequest request) {
        adminCommunityService.dismissReport(publicId, adminPublicId, ClientIpResolver.resolve(request));
        return ApiResponse.success(null);
    }
}
