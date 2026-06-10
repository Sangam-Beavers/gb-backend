package com.gb.appadmin.domain.member.controller;

import com.gb.appadmin.domain.member.dto.response.AppMemberPageResponse;
import com.gb.appadmin.domain.member.dto.response.UserActivityResponse;
import com.gb.appadmin.domain.member.service.AppMemberService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "App Admin - 회원 관리", description = "앱 관리자용 회원 조회 및 상태 변경")
@RestController
@RequestMapping("/api/v1/admin/app/members")
@RequiredArgsConstructor
public class AppMemberController {

    private final AppMemberService appMemberService;

    @Operation(summary = "회원 목록 조회/검색")
    @GetMapping
    public ApiResponse<AppMemberPageResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, name = "kyc_status") String kycStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(appMemberService.search(q, kycStatus, page, size));
    }

    @Operation(summary = "회원 계정 상태 변경 (ACTIVE/SUSPENDED)")
    @PatchMapping("/{userPublicId}/status")
    public ApiResponse<Void> changeStatus(
            @PathVariable String userPublicId,
            @RequestParam String status) {
        appMemberService.changeStatus(userPublicId, status);
        return ApiResponse.success(null);
    }

    @Operation(summary = "회원 커뮤니티 활동 제한/해제")
    @PatchMapping("/{userPublicId}/community-ban")
    public ApiResponse<Void> setCommunityBan(
            @PathVariable String userPublicId,
            @RequestParam boolean banned) {
        appMemberService.setCommunityBan(userPublicId, banned);
        return ApiResponse.success(null);
    }

    @Operation(summary = "회원 커뮤니티 활동 조회 (글+댓글)")
    @GetMapping("/{userPublicId}/activity")
    public ApiResponse<UserActivityResponse> getUserActivity(
            @PathVariable String userPublicId,
            @RequestParam(defaultValue = "0", name = "post_page") int postPage,
            @RequestParam(defaultValue = "0", name = "comment_page") int commentPage,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(appMemberService.getUserActivity(userPublicId, postPage, commentPage, size));
    }
}
