package com.gb.member.domain.admin.controller;

import com.gb.common.response.ApiResponse;
import com.gb.member.domain.admin.dto.request.KycRejectAdminRequest;
import com.gb.member.domain.admin.dto.response.AdminMemberLookupResponse;
import com.gb.member.domain.admin.dto.response.AdminMemberPageResponse;
import com.gb.member.domain.admin.dto.response.AdminMemberView;
import com.gb.member.domain.admin.dto.response.MemberDemographicsResponse;
import com.gb.member.domain.admin.dto.response.MemberStatsResponse;
import com.gb.member.domain.admin.service.MemberAdminInternalService;
import com.gb.member.domain.member.entity.MemberStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * member-service의 관리자 BFF 전용 내부 API.
 *
 * <p>경로 {@code /api/v1/internal/admin/*} 는 SecurityConfig 에서 permitAll 처리.
 * 다음 스프린트에 mTLS·NetworkPolicy로 격리. 외부 노출 금지(Ingress에서 /internal 경로 차단).
 */
@Tag(name = "[Internal] Member Admin", description = "관리자 BFF 전용 member 내부 API")
@RestController
@RequestMapping("/api/v1/internal/admin")
@RequiredArgsConstructor
public class MemberAdminInternalController {

    private final MemberAdminInternalService memberAdminInternalService;

    @Operation(summary = "[Internal] 회원 검색")
    @GetMapping("/members")
    public ApiResponse<AdminMemberPageResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, name = "kyc_status") String kycStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(memberAdminInternalService.search(q, kycStatus, page, size));
    }

    @Operation(summary = "[Internal] 회원 단건")
    @GetMapping("/members/{publicId}")
    public ApiResponse<AdminMemberView> get(@PathVariable String publicId) {
        return ApiResponse.success(memberAdminInternalService.get(publicId));
    }

    @Operation(summary = "[Internal] 회원 계정 상태 변경(ACTIVE/SUSPENDED)")
    @PatchMapping("/members/{publicId}/status")
    public ApiResponse<Void> changeStatus(
            @PathVariable String publicId,
            @RequestParam String status) {
        MemberStatus memberStatus = MemberStatus.valueOf(status.toUpperCase());
        memberAdminInternalService.changeStatus(publicId, memberStatus);
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] 커뮤니티 활동 제한/해제")
    @PatchMapping("/members/{publicId}/community-ban")
    public ApiResponse<Void> setCommunityBan(
            @PathVariable String publicId,
            @RequestParam boolean banned) {
        memberAdminInternalService.setCommunityBan(publicId, banned);
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] 커뮤니티 제한 여부 조회 (service-to-service)")
    @GetMapping("/members/{publicId}/community-status")
    public ApiResponse<CommunitySatusResponse> getCommunityStatus(@PathVariable String publicId) {
        boolean banned = memberAdminInternalService.isCommunityBanned(publicId);
        return ApiResponse.success(new CommunitySatusResponse(banned));
    }

    /** 커뮤니티 제한 상태 응답 DTO. */
    record CommunitySatusResponse(@com.fasterxml.jackson.annotation.JsonProperty("community_banned") boolean communityBanned) {}

    @Operation(summary = "[Internal] KYC 승인")
    @PostMapping("/members/{publicId}/kyc/approve")
    public ApiResponse<Void> approveKyc(@PathVariable String publicId) {
        memberAdminInternalService.approveKyc(publicId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] KYC 거절")
    @PostMapping("/members/{publicId}/kyc/reject")
    public ApiResponse<Void> rejectKyc(
            @PathVariable String publicId,
            @Valid @RequestBody KycRejectAdminRequest request) {
        memberAdminInternalService.rejectKyc(publicId, request.reason());
        return ApiResponse.success(null);
    }

    @Operation(summary = "[Internal] 회원 lookup(N+1 방지 일괄 조회)",
            description = "공백 콤마 구분 user_public_ids, 최대 100건.")
    @GetMapping("/members/lookup")
    public ApiResponse<AdminMemberLookupResponse> lookup(
            @RequestParam(name = "user_public_ids") String userPublicIds) {
        List<String> ids = userPublicIds == null || userPublicIds.isBlank()
                ? List.of()
                : Arrays.stream(userPublicIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).limit(100).toList();
        return ApiResponse.success(memberAdminInternalService.lookup(ids));
    }

    @Operation(summary = "[Internal] 회원 통계")
    @GetMapping("/stats/members")
    public ApiResponse<MemberStatsResponse> stats() {
        return ApiResponse.success(memberAdminInternalService.getStats());
    }

    @Operation(summary = "[Internal] 회원 인구통계",
            description = "성별/연령대/국적 분포(business-analytics용). 탈퇴자·관리자 제외.")
    @GetMapping("/stats/demographics")
    public ApiResponse<MemberDemographicsResponse> demographics() {
        return ApiResponse.success(memberAdminInternalService.getDemographics());
    }
}
