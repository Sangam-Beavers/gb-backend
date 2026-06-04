package com.gb.member.domain.verification.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.common.response.SuccessStatus;
import com.gb.member.domain.verification.dto.request.VerificationRequest;
import com.gb.member.domain.verification.dto.response.VerificationStatusResponse;
import com.gb.member.domain.verification.dto.response.VerificationSubmitResponse;
import com.gb.member.domain.verification.service.VerificationService;
import com.gb.member.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마이페이지 신분증 인증 API. (/api/v1/members/me/verification)
 *
 * <p>본인 식별은 검증된 JWT의 {@code public_id} claim에서 {@link CurrentUserPublicId} 리졸버로 받는다.
 * 실 신원확인 API 대신 유형별 번호 형식(정규식) 검증으로 처리하며, 통과 시 즉시 승인 + 인증 배지를 부여한다(데모).
 */
@Tag(name = "Member", description = "신분증 인증 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/verification")
public class VerificationController {

    private final VerificationService verificationService;

    @GetMapping
    @Operation(
            summary = "신분증 인증 상태 조회",
            description = "로그인한 회원 본인의 가장 최근 신분증 인증 상태(유형/상태/검토시각/요청시각)를 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공. data에 VerificationStatusResponse."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "MEMBER4001 - 존재하지 않는 회원/인증 이력 없음.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<VerificationStatusResponse> getMyVerification(
            @CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(verificationService.getMyVerification(userPublicId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "신분증 인증 요청",
            description = "외국인등록증 등 신분증 번호의 형식(정규식)을 검증해 인증을 처리한다. "
                    + "형식이 맞으면 즉시 승인되어 인증 배지(is_verified)가 부여된다. "
                    + "이미 진행중/승인된 인증이 있으면 COMMON4091로 거절한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "접수 성공. data에 VerificationSubmitResponse(status/submitted_at)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(필드 누락/신분증 유형·번호 형식 불일치).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "COMMON4091 - 이미 진행중/완료된 인증이 있습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<VerificationSubmitResponse> submitVerification(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody VerificationRequest request) {
        return ApiResponse.success(
                SuccessStatus.CREATED,
                verificationService.submitVerification(userPublicId, request));
    }
}
