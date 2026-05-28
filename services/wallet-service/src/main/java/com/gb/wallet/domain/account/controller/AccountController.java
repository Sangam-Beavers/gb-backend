package com.gb.wallet.domain.account.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.SupportedBankListResponse;
import com.gb.wallet.domain.account.service.BankAccountService;
import com.gb.wallet.domain.account.service.SupportedBankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Account", description = "계좌/은행 API")
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final SupportedBankService supportedBankService;
    private final BankAccountService bankAccountService;

    /** 추가 지원 은행 목록 조회. 🔒 JWT 필요(사용자별 결과 아님 — 마스터 데이터 조회). */
    @Operation(
            summary = "추가 지원 은행 목록 조회",
            description = "충전·현금화 계좌 등록 시 선택 가능한 활성 국내 은행 목록을 가나다순으로 반환한다. "
                    + "Beaver/Quokka Bank 등 시뮬레이션 은행도 활성 상태이면 포함된다. "
                    + "인증 미구현 상태라 현재는 X-User-Public-Id 헤더를 받지만 결과는 사용자와 무관하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 SupportedBankListResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/supported-banks")
    public ApiResponse<SupportedBankListResponse> getSupportedBanks(
            // TODO: 인증 구현 후 JWT 토큰(sub/claim)에서 userPublicId를 추출하도록 교체.
            //       현재는 인증 미구현으로 헤더(X-User-Public-Id)로 임시 수신.
            //       이 API는 사용자별 조회가 아니라 마스터 조회이므로 헤더 값 자체는 사용하지 않는다.
            @RequestHeader("X-User-Public-Id") String userPublicId) {
        return ApiResponse.success(supportedBankService.getSupportedBanks());
    }

    /** 등록된 내 계좌 목록 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "등록된 내 계좌 목록 조회",
            description = "요청 회원이 등록한 활성 은행 계좌를 주 계좌 우선, 최신 등록순으로 반환한다. "
                    + "계좌번호는 마스킹되어(앞 3 + 끝 2) 전달된다. "
                    + "등록된 계좌가 없으면 404가 아닌 200 + accounts: [] 빈 배열로 응답한다. "
                    + "인증 미구현 상태라 현재는 X-User-Public-Id 헤더로 사용자를 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 AccountListResponse가 담긴다. "
                            + "등록된 계좌가 없는 경우에도 200 + 빈 배열로 응답한다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ApiResponse<AccountListResponse> getMyAccounts(
            // TODO: 인증 구현 후 JWT 토큰(sub/claim)에서 userPublicId를 추출하도록 교체.
            //       현재는 인증 미구현으로 헤더(X-User-Public-Id)로 임시 수신.
            @RequestHeader("X-User-Public-Id") String userPublicId) {
        return ApiResponse.success(bankAccountService.getMyAccounts(userPublicId));
    }
}
