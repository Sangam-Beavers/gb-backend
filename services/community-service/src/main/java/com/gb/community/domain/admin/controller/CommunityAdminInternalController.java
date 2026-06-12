package com.gb.community.domain.admin.controller;

import com.gb.common.response.ApiResponse;
import com.gb.community.domain.admin.dto.response.AdminReportPageResponse;
import com.gb.community.domain.admin.dto.response.AdminReportedAuthorDetailResponse;
import com.gb.community.domain.admin.dto.response.AdminReportedAuthorView;
import com.gb.community.domain.admin.dto.response.AdminUserActivityResponse;
import com.gb.community.domain.admin.dto.response.ReportStatsResponse;
import com.gb.community.domain.admin.service.CommunityAdminInternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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

    @Operation(summary = "[Internal] 신고 콘텐츠 목록 (실제 reports 집계)",
            description = "status=PENDING|RESOLVED_DELETED|DISMISSED, reason=SPAM|ABUSE|FRAUD|SEXUAL|ETC 로 필터. null이면 전체.")
    @GetMapping("/reports")
    public ApiResponse<AdminReportPageResponse> reports(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                communityAdminInternalService.reports(status, reason, page, size));
    }

    @Operation(summary = "[Internal] 신고당한 작성자 집계 목록")
    @GetMapping("/reports/by-author")
    public ApiResponse<List<AdminReportedAuthorView>> reportedAuthors() {
        return ApiResponse.success(communityAdminInternalService.reportedAuthors());
    }

    @Operation(summary = "[Internal] 특정 작성자 신고 상세")
    @GetMapping("/reports/by-author/{authorPublicId}")
    public ApiResponse<AdminReportedAuthorDetailResponse> reportedAuthorDetail(
            @PathVariable String authorPublicId) {
        return ApiResponse.success(
                communityAdminInternalService.reportedAuthorDetail(authorPublicId));
    }

    @Operation(summary = "[Internal] 게시글 숨김 → soft-delete + 연관 reports RESOLVED_DELETED")
    @PostMapping("/posts/{publicId}/hide")
    public ApiResponse<Void> hide(@PathVariable String publicId) {
        communityAdminInternalService.hidePost(publicId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] 게시글 삭제 → soft-delete + 연관 reports RESOLVED_DELETED")
    @DeleteMapping("/posts/{publicId}")
    public ApiResponse<Void> deletePost(@PathVariable String publicId) {
        communityAdminInternalService.deletePost(publicId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] 댓글 삭제 → soft-delete + 연관 reports RESOLVED_DELETED (신규)")
    @DeleteMapping("/comments/{commentPublicId}")
    public ApiResponse<Void> deleteComment(@PathVariable String commentPublicId) {
        communityAdminInternalService.deleteComment(commentPublicId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] 신고 통계 — 실제 PENDING 카운트")
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
                communityAdminInternalService.getUserActivity(
                        userPublicId, post_page, comment_page, size));
    }
}
