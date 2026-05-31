package com.gb.member.domain.member.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 조회 계열 API. 가입 전 이메일/닉네임 중복 확인 등 인증이 필요 없는 공개 조회를 담당한다.
 *
 * <p>가입 자체(/api/v1/auth/register)는 {@link MemberController}가 담당하고, 본 컨트롤러는
 * 가입 폼의 "중복확인" 버튼이 호출하는 사전 확인용 GET 엔드포인트를 제공한다.
 *
 * <p>{@code @Validated}가 있어야 {@code @RequestParam}의 제약(@Email/@NotBlank)이 활성화된다.
 * 위반 시 ConstraintViolationException → GlobalExceptionHandler가 COMMON4001(400)로 변환한다.
 */
@Tag(name = "Member", description = "회원 조회 API")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/members")
public class MemberQueryController {

    private final MemberService memberService;

    @GetMapping("/check-email")
    @Operation(
            summary = "이메일 중복 확인",
            description = "이메일이 사용 가능한지(중복이 아닌지) 확인한다. available=true면 사용 가능.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "확인 성공. data.available 로 사용 가능 여부 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 이메일이 비었거나 형식이 올바르지 않습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<CheckAvailabilityResponse> checkEmail(
            @RequestParam
            @NotBlank(message = "이메일은 필수입니다")
            @Email(message = "이메일 형식이 올바르지 않습니다") String email) {
        return ApiResponse.success(memberService.checkEmail(email));
    }

    @GetMapping("/check-nickname")
    @Operation(
            summary = "닉네임 중복 확인",
            description = "닉네임이 사용 가능한지(중복이 아닌지) 확인한다. available=true면 사용 가능.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "확인 성공. data.available 로 사용 가능 여부 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 닉네임이 비어 있습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<CheckAvailabilityResponse> checkNickname(
            @RequestParam
            @NotBlank(message = "닉네임은 필수입니다") String nickname) {
        return ApiResponse.success(memberService.checkNickname(nickname));
    }
}
