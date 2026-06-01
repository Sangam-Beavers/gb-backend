package com.gb.wallet.domain.wallet.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletBalanceResponse;
import com.gb.wallet.domain.wallet.service.WalletService;
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

@Tag(name = "Wallet", description = "전자지갑/잔액 API")
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    // 응답별 ErrorResponse 예시 JSON. ErrorCode enum의 (code, message)와 1:1 일치하도록 손으로 박는다.
    // (common ErrorResponse 클래스 레벨 example을 응답별로 override 하기 위함 — 안 그러면 모든 에러 응답이
    //  ErrorResponse.@Schema에 박힌 단일 디폴트(WALLET4001)로 표시됨.)
    private static final String EX_WALLET4001 =
            "{\"success\":false,\"code\":\"WALLET4001\",\"message\":\"존재하지 않는 지갑입니다.\"}";
    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";

    private final WalletService walletService;

    /** 전자지갑 잔액 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "내 전자지갑 잔액 조회",
            description = "인증된 사용자의 전자지갑과 통화별 잔액 목록을 조회한다. "
                    + "잔액은 명세에 따라 소수점 4자리 string으로 응답한다. "
                    + "사용자는 JWT의 public_id claim으로 식별한다.")
    // 비즈니스 코드(WALLET4001 등)는 HTTP 상태와 별개이므로 responseCode에는 HTTP 상태를,
    // description에 "비즈니스 코드 - 의미"를 적는다. 실패 응답 본문은 공통 ErrorResponse 구조.
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 WalletBalanceResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "WALLET4001 - 존재하지 않는 지갑(해당 사용자의 지갑 없음).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "WALLET4001", value = EX_WALLET4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/me/balances")
    public ApiResponse<WalletBalanceResponse> getMyBalances(
            @CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(walletService.getMyBalances(userPublicId));
    }
}
