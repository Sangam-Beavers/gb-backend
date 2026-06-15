package com.gb.wallet.domain.security.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.security.dto.response.SecuritySummaryResponse;
import com.gb.wallet.domain.security.service.SecurityService;
import com.gb.wallet.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전자지갑 보안 점검 API. 기존 거래 데이터를 규칙 기반으로 점검한 요약을 제공한다(읽기 전용, 새 테이블 없음).
 * 홈 "이상거래 탐지" 카드 + 보안 점검 상세 화면용. WalletController 와 동일한 base path 하위에 둔다.
 */
@Tag(name = "WalletSecurity", description = "전자지갑 보안 점검 API")
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class SecurityController {

    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";

    private final SecurityService securityService;

    /** 전자지갑 보안 점검 요약. 🔒 JWT 필요. */
    @Operation(
            summary = "내 전자지갑 보안 점검",
            description = "인증된 사용자의 최근 30일(최대 100건) 거래를 규칙 기반으로 점검해 종합 상태(SAFE/WARNING), "
                    + "항목별 결과, 주의 거래 목록을 반환한다. 새 데이터를 만들지 않고 기존 거래를 읽어 계산만 한다. "
                    + "지갑이 없는 신규 사용자는 404 대신 안전(SAFE) 빈 요약을 받는다. 사용자는 JWT의 public_id claim 으로 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "점검 성공. 응답은 공통 ApiResponse 로 감싸지며 data 에 SecuritySummaryResponse 가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/me/security-summary")
    public ApiResponse<SecuritySummaryResponse> getMySecuritySummary(
            @CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(securityService.getSecuritySummary(userPublicId));
    }
}
