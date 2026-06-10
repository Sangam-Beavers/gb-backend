package com.gb.wallet.domain.account.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.account.dto.request.ChargeRequest;
import com.gb.wallet.domain.account.dto.request.ConfirmAccountRequest;
import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.ChargeResponse;
import com.gb.wallet.domain.account.dto.response.ConfirmAccountResponse;
import com.gb.wallet.domain.account.dto.response.SupportedBankListResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.service.BankAccountService;
import com.gb.wallet.domain.account.service.ChargeService;
import com.gb.wallet.domain.account.service.HolderService;
import com.gb.wallet.domain.account.service.SupportedBankService;
import com.gb.wallet.global.common.util.ClientIpResolver;
import com.gb.wallet.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Account", description = "계좌/은행 API")
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Validated // @RequestParam 등 메서드 파라미터의 @NotBlank/@NotNull 검증을 활성화한다.
public class AccountController {

    private final SupportedBankService supportedBankService;
    private final BankAccountService bankAccountService;
    private final HolderService holderService;
    private final ChargeService chargeService;

    /** 추가 지원 은행 목록 조회. 🔒 JWT 필요(사용자별 결과 아님 — 마스터 데이터 조회). */
    @Operation(
            summary = "추가 지원 은행 목록 조회",
            description = "충전·현금화 계좌 등록 시 선택 가능한 활성 국내 은행 목록을 가나다순으로 반환한다. "
                    + "Beaver/Quokka Bank 등 시뮬레이션 은행도 활성 상태이면 포함된다. "
                    + "마스터 데이터 조회라 결과는 사용자와 무관하지만 인증은 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 SupportedBankListResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/supported-banks")
    public ApiResponse<SupportedBankListResponse> getSupportedBanks() {
        return ApiResponse.success(supportedBankService.getSupportedBanks());
    }

    /** 등록된 내 계좌 목록 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "등록된 내 계좌 목록 조회",
            description = "요청 회원이 등록한 활성 은행 계좌를 주 계좌 우선, 최신 등록순으로 반환한다. "
                    + "계좌번호는 마스킹되어(앞 3 + 끝 2) 전달된다. "
                    + "등록된 계좌가 없으면 404가 아닌 200 + accounts: [] 빈 배열로 응답한다. "
                    + "사용자는 JWT의 public_id claim으로 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 AccountListResponse가 담긴다. "
                            + "등록된 계좌가 없는 경우에도 200 + 빈 배열로 응답한다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ApiResponse<AccountListResponse> getMyAccounts(
            @CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(bankAccountService.getMyAccounts(userPublicId));
    }

    /** 예금주 실명 조회. 🔒 JWT 필요. 외부 Mock 은행 호출만 수행하며 본체 DB는 만지지 않는다. */
    @Operation(
            summary = "예금주 실명 조회",
            description = "은행 코드와 계좌번호로 외부 Mock 은행(Beaver/Quokka Bank)에 조회해 예금주 실명을 가져온다. "
                    + "계좌 등록(/accounts/verify)을 시작하기 전에 사용자가 입력한 계좌번호의 예금주를 보여주는 용도다. "
                    + "본체 DB에는 아무것도 쓰지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 AccountHolderResponse(account_holder_name)가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "ACCOUNT4001 - 존재하지 않는 계좌입니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "COMMON4291 - 조회 횟수를 초과했습니다(예금주 조회 rate-limit, WACC-03).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "COMMON5031 - 일시적으로 처리할 수 없습니다(Mock 은행 통신 장애).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/holder")
    public ApiResponse<AccountHolderResponse> getAccountHolder(
            @RequestParam("bankCode") @NotBlank @Size(max = 20) String bankCode,
            @RequestParam("accountNumber") @NotBlank @Size(max = 100) String accountNumber,
            @CurrentUserPublicId String userPublicId) {
        // userPublicId는 PII enumeration 방어 rate-limit 키로 쓰인다(WACC-03).
        return ApiResponse.success(holderService.getAccountHolder(bankCode, accountNumber, userPublicId));
    }

    /** 계좌 인증 1단계 — 1원 소액이체 요청. 🔒 JWT 필요. 본체 DB 미사용. */
    @Operation(
            summary = "계좌 인증 1단계 — 1원 소액이체 요청",
            description = "은행 코드/계좌번호/예금주명으로 외부 Mock 은행에 1원을 입금한다. "
                    + "입금 적요에 4자리 인증번호가 기재되며 유효 시간은 10분이다. "
                    + "account_token은 발급되지 않으며, 사용자가 적요의 인증번호를 확인한 뒤 "
                    + "POST /accounts/confirm으로 제출해야 한다. 본체 DB에는 아무것도 쓰지 않는다. "
                    + "사용자 단위 rate-limit 적용(WACC-02).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "1원 입금 성공. data.pending=true, data.expires_at 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다. / ACCOUNT4002 - 예금주 불일치.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "ACCOUNT4001 - 존재하지 않는 계좌입니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "ACCOUNT4005 - 계좌 인증 요청 횟수를 초과했습니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "COMMON5031 - Mock 은행 통신 장애.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/verify")
    public ApiResponse<VerifyAccountResponse> verifyAccount(
            @Valid @RequestBody VerifyAccountRequest request,
            @CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(bankAccountService.verifyAccount(request, userPublicId));
    }

    /** 계좌 인증 2단계 — 인증번호 확인. 🔒 JWT 필요. */
    @Operation(
            summary = "계좌 인증 2단계 — 인증번호 확인",
            description = "POST /accounts/verify로 입금된 적요의 4자리 인증번호를 제출해 "
                    + "account_token을 발급받는다. token은 서버 세션(Redis, 10분)에 저장되며 "
                    + "클라이언트에게는 노출되지 않는다. 이후 POST /accounts(계좌 등록) 시 서버가 "
                    + "세션을 원자 소비해 등록을 완료한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "인증 성공. data.verified=true 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "ACCOUNT4008 - 인증번호가 올바르지 않습니다. / ACCOUNT4009 - 인증 세션 없음/만료.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "ACCOUNT4001 - 존재하지 않는 계좌입니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(세션 저장 실패 등).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "COMMON5031 - Mock 은행 통신 장애.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/confirm")
    public ApiResponse<ConfirmAccountResponse> confirmAccount(
            @Valid @RequestBody ConfirmAccountRequest request,
            @CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(bankAccountService.confirmAccount(request, userPublicId));
    }

    /** 계좌 등록 최종 완료. 🔒 JWT 필요. */
    @Operation(
            summary = "계좌 등록 최종 완료",
            description = "verify에서 발급받은 account_token과 계좌 정보를 받아 bank_accounts에 INSERT한다. "
                    + "사용자의 첫 등록 계좌는 자동으로 주 계좌(is_primary=true)로 설정된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "등록 성공. data에 AccountResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(필수값 누락/지원하지 않는 은행 코드).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "ACCOUNT4004 - 이미 등록된 계좌입니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "COMMON5031 - 일시적으로 처리할 수 없습니다(등록 직렬화 분산락 획득 실패).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AccountResponse> registerAccount(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody RegisterAccountRequest request) {
        return ApiResponse.success(bankAccountService.registerAccount(userPublicId, request));
    }

    /** 충전 금액 검증·실행. 🔒 JWT 필요. 등록 계좌로 Mock 은행 출금 → 본체 KRW 잔액 증액. */
    @Operation(
            summary = "충전 금액 검증·실행",
            description = "등록된 계좌의 mock_account_token으로 Mock 은행에 출금을 요청해 본체 KRW 잔액을 증액한다. "
                    + "동일 Idempotency-Key 재요청 시 첫 응답을 부수효과 없이 재반환한다(멱등성).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "충전 성공. 응답은 공통 ApiResponse로 감싸지며 data에 ChargeResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(금액 누락/형식 오류/0 이하, 필수 헤더 누락). "
                            + "/ ACCOUNT4003 - 연동 계좌의 잔액이 부족합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "ACCOUNT4006 - 인증되지 않은 계좌입니다(토큰 없음 또는 Mock 토큰 무효).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "ACCOUNT4001 - 존재하지 않는 계좌입니다. / WALLET4001 - 존재하지 않는 지갑입니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "ACCOUNT4007 - 충전 한도를 초과했습니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(멱등성 일관성 위반 등 정상 불가 상태).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "COMMON5031 - 일시적으로 처리할 수 없습니다(Mock 은행 통신 장애/타임아웃).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/charge")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChargeResponse> charge(
            @CurrentUserPublicId String userPublicId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @PathVariable("id") @NotBlank @Size(max = 36) String accountPublicId,
            @Valid @RequestBody ChargeRequest request,
            HttpServletRequest httpRequest) {
        // 프록시/LB 뒤에서는 X-Forwarded-For의 최초 클라이언트 IP를 기록한다(신뢰 프록시 전제, audit 참고용).
        String clientIp = ClientIpResolver.resolve(httpRequest);
        return ApiResponse.success(chargeService.charge(
                userPublicId, accountPublicId, idempotencyKey, request, clientIp));
    }

    /** 주 계좌 변경. 🔒 JWT 필요. 기존 주 계좌는 자동 해제(사용자당 주 계좌 1개). */
    @Operation(
            summary = "주 계좌 변경",
            description = "요청 회원의 활성 계좌 중 하나를 주 계좌로 지정한다. 기존 주 계좌는 자동으로 해제되어 "
                    + "사용자당 주 계좌가 항상 1개로 유지된다. 이미 주 계좌인 계좌를 다시 지정하면 멱등 성공(200)이다. "
                    + "data에 변경된 AccountResponse(is_primary=true)가 담긴다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "변경 성공. data에 변경된 AccountResponse가 담긴다. 이미 주 계좌인 경우에도 멱등 200."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "ACCOUNT4001 - 존재하지 않는 계좌입니다(미존재/타인/비활성 계좌, 사유 구분 없음).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "COMMON5031 - 일시적으로 처리할 수 없습니다(계좌 변경 직렬화 분산락 획득 실패).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{id}/primary")
    public ApiResponse<AccountResponse> changePrimary(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String accountPublicId) {
        return ApiResponse.success(bankAccountService.changePrimary(userPublicId, accountPublicId));
    }

    /** 계좌 삭제(soft-delete). 🔒 JWT 필요. 주 계좌 삭제 시 남은 활성 계좌 1건을 자동 승격. */
    @Operation(
            summary = "계좌 삭제",
            description = "지정한 계좌를 삭제(soft-delete, is_active=false)한다. 주 계좌를 삭제하면 남은 활성 계좌 중 "
                    + "가장 최근 등록 1건이 자동으로 주 계좌로 승격된다(마지막 1개면 주 계좌 없는 상태 허용). "
                    + "성공 시 200 + data:null.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "삭제 성공(soft-delete). data는 null이다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "ACCOUNT4001 - 존재하지 않는 계좌입니다(미존재/타인/이미 비활성 계좌).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "COMMON5031 - 일시적으로 처리할 수 없습니다(계좌 변경 직렬화 분산락 획득 실패).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteAccount(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String accountPublicId) {
        bankAccountService.deleteAccount(userPublicId, accountPublicId);
        return ApiResponse.success(null); // 200 + data:null (envelope에서 data 노출)
    }
}
