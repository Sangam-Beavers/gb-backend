package com.gb.community.domain.admin.controller;

import com.gb.common.response.ApiResponse;
import com.gb.community.domain.admin.dto.response.AdminReportPageResponse;
import com.gb.community.domain.admin.dto.response.AdminUserActivityResponse;
import com.gb.community.domain.admin.dto.response.ReportStatsResponse;
import com.gb.community.domain.admin.service.CommunityAdminInternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[Internal] Community Admin", description = "관리자 BFF 전용 community 내부 API")
@RestController
@RequestMapping("/api/v1/internal/admin")
@RequiredArgsConstructor
public class CommunityAdminInternalController {

    private final CommunityAdminInternalService communityAdminInternalService;

    @Operation(summary = "[Internal] 신고 게시글 페이지")
    @GetMapping("/reports")
    public ApiResponse<AdminReportPageResponse> reports(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(communityAdminInternalService.reports(category, page, size));
    }

    @Operation(summary = "[Internal] 게시글 숨김")
    @PostMapping("/posts/{publicId}/hide")
    public ApiResponse<Void> hide(@PathVariable String publicId) {
        communityAdminInternalService.hidePost(publicId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] 게시글 삭제")
    @DeleteMapping("/posts/{publicId}")
    public ApiResponse<Void> delete(@PathVariable String publicId) {
        communityAdminInternalService.deletePost(publicId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] 신고 통계")
    @GetMapping("/stats/reports")
    public ApiResponse<ReportStatsResponse> stats() {
        return ApiResponse.success(communityAdminInternalService.stats());
    }

    @Operation(summary = "[Internal] 회원 커뮤니티 활동 조회(글+댓글)")
    @GetMapping("/members/{userPublicId}/activity")
    public ApiResponse<AdminUserActivityResponse> userActivity(
            @PathVariable String userPublicId,
            @RequestParam(defaultValue = "0") int post_page,
            @RequestParam(defaultValue = "0") int comment_page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(
                communityAdminInternalService.getUserActivity(userPublicId, post_page, comment_page, size));
    }
}
