package com.gb.member.domain.milestone.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.member.domain.milestone.dto.response.TrustMilestonesResponse;
import com.gb.member.domain.milestone.service.MilestoneService;
import com.gb.member.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인 신뢰등급 마일스톤 현황 API (Phase 2 — BE-6).
 *
 * <p>프론트 마이페이지 등급 바텀시트(진척도 + 다음 단계 CTA)가 소비한다(FE-4). 로직 없이
 * Service 호출 + ApiResponse 래핑만(CLAUDE §4). 본인 식별은 JWT claim {@code public_id}
 * (@CurrentUserPublicId — §9 방식 B).
 */
@Tag(name = "Member", description = "회원 본인(마이페이지) API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/trust-milestones")
public class TrustMilestoneController {

    private final MilestoneService milestoneService;

    @GetMapping
    @Operation(
            summary = "내 신뢰등급 마일스톤 현황 조회",
            description = "현재 신뢰등급(trust_grade — NEWCOMER/VERIFIED/CONNECTED/TRUSTED)과 마일스톤 "
                    + "카탈로그 전체(미달성 포함)를 조회한다. 카탈로그는 등급 체인 순서 고정 3종: "
                    + "ID_VERIFIED(members.is_verified로 합성, achieved_at은 인증 승인 시각 또는 null) / "
                    + "BANK_ACCOUNT_CONNECTED / FIRST_TRANSACTION_COMPLETED(member_milestones 기록). "
                    + "각 항목은 { milestone_type, achieved, achieved_at(null 가능) }.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 TrustMilestonesResponse(trust_grade + milestones[])."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.(토큰 누락/무효)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "MEMBER4001 - 존재하지 않는 회원입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<TrustMilestonesResponse> getMyTrustMilestones(
            @CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(milestoneService.getMyTrustMilestones(userPublicId));
    }
}
