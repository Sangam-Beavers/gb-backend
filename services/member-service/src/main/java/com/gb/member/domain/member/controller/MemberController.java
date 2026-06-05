package com.gb.member.domain.member.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.common.response.SuccessStatus;
import com.gb.member.domain.member.dto.request.PasswordResetEmailRequest;
import com.gb.member.domain.member.dto.request.PasswordResetRequest;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "회원가입",
            description = "이메일, 비밀번호, 이름, 닉네임, 국적, 주 사용 언어로 회원가입한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "가입 성공. 응답은 공통 ApiResponse로 감싸지며 data에 SignupResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(@Valid 실패 등).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "MEMBER4002 - 이메일 중복 / MEMBER4003 - 닉네임 중복 / "
                            + "COMMON4091 - 동시 가입 race로 이미 존재(existsBy 통과 후 UNIQUE 백스톱, member-5).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.success(SuccessStatus.CREATED, memberService.signup(request));
    }

    @PostMapping("/password/reset-request")
    @Operation(
            summary = "비밀번호 재설정 링크 발송",
            description = "가입 이메일로 비밀번호 재설정 링크를 발송한다. 가입 여부 노출 방지를 위해 "
                    + "미가입 이메일이어도 동일하게 200을 반환한다(실제 발송은 가입된 경우만).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "요청 접수(가입된 이메일이면 메일 발송)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "COMMON4001 - 요청 값이 올바르지 않습니다(이메일 형식 등).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<Void> sendPasswordResetEmail(@Valid @RequestBody PasswordResetEmailRequest request) {
        memberService.sendPasswordResetEmail(request);
        return ApiResponse.success(null);
    }

    @PostMapping("/password/reset")
    @Operation(
            summary = "비밀번호 재설정",
            description = "메일 링크의 토큰과 새 비밀번호로 비밀번호를 변경한다(IdP 경유).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "비밀번호 변경 완료."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값 오류 / MEMBER4004 - 유효하지 않거나 만료된 재설정 토큰.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500", description = "COMMON5000 - IdP 비밀번호 변경 실패 등 서버 오류.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        memberService.resetPassword(request);
        return ApiResponse.success(null);
    }
}
