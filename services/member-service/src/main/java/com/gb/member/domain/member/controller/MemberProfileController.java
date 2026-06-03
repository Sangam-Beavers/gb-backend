package com.gb.member.domain.member.controller;

import com.gb.common.exception.AuthErrorCode;
import com.gb.common.exception.BusinessException;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.common.response.SuccessStatus;
import com.gb.member.domain.member.dto.request.ProfileUpdateRequest;
import com.gb.member.domain.member.dto.request.SocialProfileRequest;
import com.gb.member.domain.member.dto.response.ProfileResponse;
import com.gb.member.domain.member.dto.response.SocialProfileResponse;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증된 본인(/members/me) 대상 회원 명령 API. 현재는 소셜 가입 추가정보 보완 1개를 담당한다.
 *
 * <p>⚠️ {@code /api/v1/members/me} 네임스페이스는 마이페이지(프로필 조회/수정 등, 다른 담당자) 와 공유된다.
 * 여기서는 소셜 로그인 흐름의 일부인 {@code social-profile}만 소유한다. 마이페이지 컨트롤러가 별도로 추가돼도
 * full path가 겹치지 않으면 충돌하지 않는다(경로/소유는 팀과 합의).
 *
 * <p>본인 식별과 email/name은 검증된 토큰(JWT claim)에서 직접 읽는다 — 이 엔드포인트는 public_id 한 개가
 * 아니라 email/name/sub 까지 여러 claim이 필요해, 단일 claim 편의용 ArgumentResolver(@CurrentUserPublicId)
 * 대신 {@link Jwt}를 직접 받는다. 식별에 필요한 claim이 비면 AUTH4011로 fail-fast 한다.
 */
@Tag(name = "Member", description = "회원 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me")
public class MemberProfileController {

    private final MemberService memberService;

    /** IdP가 토큰에 실어 보내는 회원 식별 claim 이름. */
    private static final String CLAIM_PUBLIC_ID = "public_id";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";

    @PostMapping("/social-profile")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "소셜 가입 후 추가 정보 보완",
            description = "Google 등 소셜 로그인으로 처음 가입한 회원이 닉네임/국적/언어를 입력해 가입을 완료한다. "
                    + "이메일·이름은 토큰(JWT claim)에서 채우며, 이 요청이 성공하면 회원(members) 정보가 최초 생성된다. "
                    + "이미 완료된 회원이 다시 호출하면 COMMON4091로 거절한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "보완 완료. data에 SocialProfileResponse(public_id/email/nickname)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(@Valid 실패 등).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다(토큰 없음/무효 또는 식별 claim 누락).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "COMMON4091 - 이미 가입 완료된 회원 / MEMBER4002 - 이메일 중복 / MEMBER4003 - 닉네임 중복.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<SocialProfileResponse> completeSocialProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SocialProfileRequest request) {

        String publicId = jwt.getClaimAsString(CLAIM_PUBLIC_ID);
        String email = jwt.getClaimAsString(CLAIM_EMAIL);
        String name = jwt.getClaimAsString(CLAIM_NAME);
        String authProviderId = jwt.getSubject(); // sub

        // 회원 생성에 필요한 식별/필수 claim이 비면 진행 불가 → AUTH4011 fail-fast.
        if (!StringUtils.hasText(publicId) || !StringUtils.hasText(email) || !StringUtils.hasText(name)) {
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
        }

        return ApiResponse.success(
                SuccessStatus.CREATED,
                memberService.completeSocialProfile(publicId, email, name, authProviderId, request));
    }

    @GetMapping
    @Operation(
            summary = "내 프로필 조회",
            description = "마이페이지 내 프로필을 조회한다. is_verified/temperature_grade/profile_image_url은 "
                    + "각각 인증·커뮤니티·이미지 도메인 소관이라 현재 기본값으로 내려간다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공. data에 ProfileResponse."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "MEMBER4001 - 존재하지 않는 회원입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<ProfileResponse> getMyProfile(@CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(memberService.getMyProfile(userPublicId));
    }

    @PatchMapping
    @Operation(
            summary = "내 프로필 수정",
            description = "닉네임/주 사용 언어/자기소개를 수정한다. 닉네임을 다른 값으로 바꿀 때만 중복 확인한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "수정 성공. data에 변경된 ProfileResponse."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "COMMON4001 - 요청 값이 올바르지 않습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "MEMBER4001 - 존재하지 않는 회원입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "MEMBER4003 - 이미 사용 중인 닉네임입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<ProfileResponse> updateMyProfile(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.success(memberService.updateMyProfile(userPublicId, request));
    }
}
