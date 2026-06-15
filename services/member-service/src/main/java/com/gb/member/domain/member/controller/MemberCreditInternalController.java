package com.gb.member.domain.member.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.member.domain.member.dto.response.CreditResponse;
import com.gb.member.domain.member.service.MemberCreditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서류 분석 크레딧 내부 API(이슈 #244) — document-service 전용.
 * SecurityConfig에서 {@code /api/v1/internal/members/**}를 permitAll 처리한다.
 * 운영 환경에서는 NetworkPolicy·mTLS로 외부 직접 호출을 차단한다.
 */
@Tag(name = "Member Credit (Internal)", description = "서류 분석 크레딧 내부 API — document-service 전용")
@RestController
@RequestMapping("/api/v1/internal/members")
@RequiredArgsConstructor
public class MemberCreditInternalController {

    private final MemberCreditService memberCreditService;

    @Operation(
            summary = "크레딧 조회",
            description = "document-service가 서류 제출 전 잔여 크레딧을 확인하기 위해 호출한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "MEMBER4001 - 존재하지 않는 회원입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{userPublicId}/credit")
    public ApiResponse<CreditResponse> getCredit(@PathVariable String userPublicId) {
        return ApiResponse.success(memberCreditService.getCredit(userPublicId));
    }

    @Operation(
            summary = "크레딧 차감",
            description = "document-service가 서류 분석 제출 시 크레딧을 1 차감한다. "
                    + "DB 레벨 원자적 UPDATE로 race condition을 방지한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공 — 차감 후 잔여 크레딧 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "MEMBER4001 - 존재하지 않는 회원입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "MEMBER4007 - 서류 분석 크레딧이 부족합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{userPublicId}/credit/use")
    public ApiResponse<CreditResponse> useCredit(@PathVariable String userPublicId) {
        return ApiResponse.success(memberCreditService.useCredit(userPublicId));
    }
}
