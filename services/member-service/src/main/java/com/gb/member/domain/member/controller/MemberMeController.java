package com.gb.member.domain.member.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.member.domain.member.dto.request.LanguageUpdateRequest;
import com.gb.member.domain.member.dto.response.LanguageResponse;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증된 회원 본인의 "내 정보" API. 본인 식별이 필요한 엔드포인트(언어 설정, 탈퇴)를 모은다.
 *
 * <p>가입 전 공개 조회(중복 확인)는 {@link MemberQueryController}가, 가입 자체는
 * {@link MemberController}가 담당한다. 본 컨트롤러는 모두 인증이 필요하며, 본인 식별자는
 * JWT custom claim {@code public_id}에서 {@link CurrentUserPublicId}로 주입받는다(CLAUDE §9 방식 B).
 */
@Tag(name = "Member", description = "회원 본인(마이페이지) API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me")
public class MemberMeController {

    private final MemberService memberService;

    @GetMapping("/language")
    @Operation(
            summary = "언어 설정 조회",
            description = "현재 로그인한 회원의 주 사용 언어(BCP 47)를 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data.language 로 주 사용 언어 반환."),
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
    public ApiResponse<LanguageResponse> getLanguage(@CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(memberService.getLanguage(userPublicId));
    }

    @PatchMapping("/language")
    @Operation(
            summary = "언어 설정 변경",
            description = "현재 로그인한 회원의 주 사용 언어(BCP 47)를 변경하고 변경된 값을 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "변경 성공. data.language 로 변경된 언어 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 언어가 비어 있습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
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
    public ApiResponse<LanguageResponse> updateLanguage(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody LanguageUpdateRequest request) {
        return ApiResponse.success(memberService.updateLanguage(userPublicId, request.getLanguage()));
    }

    @DeleteMapping
    @Operation(
            summary = "회원 탈퇴",
            description = "현재 로그인한 회원을 탈퇴 처리한다. 로컬은 soft delete(deleted_at)로 보존하고, "
                    + "외부 IdP(Authentik) 사용자는 비활성화(is_active=false)해 이후 로그인/토큰 발급을 막는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "탈퇴 성공. data 는 null."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.(토큰 누락/무효)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "MEMBER4001 - 존재하지 않는 회원입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - IdP 연동 실패로 탈퇴를 완료하지 못했습니다.(롤백)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<Void> withdraw(@CurrentUserPublicId String userPublicId) {
        memberService.withdraw(userPublicId);
        return ApiResponse.success(null);
    }
}
