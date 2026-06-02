package com.gb.wallet.domain.transaction.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.transaction.dto.response.TransactionListResponse;
import com.gb.wallet.domain.transaction.service.TransactionService;
import com.gb.wallet.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wallet", description = "전자지갑/잔액 API") // 잔액 조회(WalletController)와 같은 그룹으로 묶는다.
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
@Validated // @RequestParam page/size의 @Min/@Max 검증을 활성화한다.
public class TransactionController {

    private final TransactionService transactionService;

    /** 전자지갑 거래내역 조회. 🔒 JWT 필요. 본인 전 유형 거래를 최근순 페이지 조회. */
    @Operation(
            summary = "전자지갑 거래내역 조회",
            description = "본인의 모든 유형(CHARGE/INTERNAL_TRANSFER/REMITTANCE/EXCHANGE) 거래를 최근순으로 "
                    + "페이지 조회한다. data.transactions 배열 + 페이지 메타(page/size/total_elements/total_pages). "
                    + "거래가 없으면 200 + 빈 배열로 응답한다. 사용자는 JWT의 public_id claim으로 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 TransactionListResponse가 담긴다. 거래가 없으면 빈 배열."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - page/size 범위 위반(page<0 또는 size 1~100 초과).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me/transactions")
    public ApiResponse<TransactionListResponse> getMyTransactions(
            @CurrentUserPublicId String userPublicId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(transactionService.getMyTransactions(userPublicId, page, size));
    }
}
