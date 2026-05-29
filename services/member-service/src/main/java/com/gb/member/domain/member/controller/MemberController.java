package com.gb.member.domain.member.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.common.response.SuccessStatus;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "인증 API")
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "회원가입", description = "이메일, 비밀번호, 이름, 닉네임, 국적, 주 사용 언어로 회원가입")
    // 비즈니스 코드(MEMBER4002 등)는 HTTP 상태와 별개이므로 responseCode에는 HTTP 상태를,
    // description에 "비즈니스 코드 - 의미"를 적는다. examples로 실제 응답 바디 샘플을 노출한다.
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "가입 성공. data에 SignupResponse가 담긴다.",
                    content = @Content(
                            schema = @Schema(implementation = ApiResponse.class),
                            examples = @ExampleObject(
                                    name = "성공",
                                    value = """
                                            {
                                              "success": true,
                                              "data": {
                                                "member_id": 1,
                                                "email": "user@example.com",
                                                "nickname": "young2z1"
                                              },
                                              "message": "성공적으로 생성되었습니다."
                                            }
                                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(@Valid 실패 등).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "COMMON4001",
                                    value = """
                                            {
                                              "success": false,
                                              "code": "COMMON4001",
                                              "message": "요청 값이 올바르지 않습니다."
                                            }
                                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "MEMBER4002 - 이메일 중복 / MEMBER4003 - 닉네임 중복.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "MEMBER4002 (이메일 중복)",
                                            value = """
                                                    {
                                                      "success": false,
                                                      "code": "MEMBER4002",
                                                      "message": "이미 사용 중인 이메일입니다."
                                                    }
                                                    """),
                                    @ExampleObject(
                                            name = "MEMBER4003 (닉네임 중복)",
                                            value = """
                                                    {
                                                      "success": false,
                                                      "code": "MEMBER4003",
                                                      "message": "이미 사용 중인 닉네임입니다."
                                                    }
                                                    """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "COMMON4221 - 처리할 수 없는 요청입니다.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "COMMON4221",
                                    value = """
                                            {
                                              "success": false,
                                              "code": "COMMON4221",
                                              "message": "처리할 수 없는 요청입니다."
                                            }
                                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "COMMON5000",
                                    value = """
                                            {
                                              "success": false,
                                              "code": "COMMON5000",
                                              "message": "서버 오류가 발생했습니다."
                                            }
                                            """)))
    })
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.success(SuccessStatus.CREATED, memberService.signup(request));
    }
}
