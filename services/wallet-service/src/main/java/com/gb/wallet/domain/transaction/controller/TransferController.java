package com.gb.wallet.domain.transaction.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.dto.request.TransferFeeRequest;
import com.gb.wallet.domain.transaction.dto.request.ValidateScheduledRequest;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferFeeResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferReceiptResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateScheduledResponse;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfer", description = "송금 관련 API")
@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
// @Validated: 메서드 파라미터(@RequestParam @Email 등)의 Bean Validation을 활성화한다.
// 위반 시 ConstraintViolationException → GlobalExceptionHandler에서 COMMON4001(400)으로 변환.
@Validated
public class TransferController {

    // 응답별 ErrorResponse 예시 JSON. ErrorCode enum의 (code, message)와 1:1 일치하도록 손으로 박는다.
    // (common ErrorResponse 클래스 레벨 example을 응답별로 override 하기 위함 — 안 그러면 모든 에러 응답이
    //  ErrorResponse.@Schema에 박힌 단일 디폴트(WALLET4001)로 표시됨.)
    private static final String EX_COMMON4001 =
            "{\"success\":false,\"code\":\"COMMON4001\",\"message\":\"요청 값이 올바르지 않습니다.\"}";
    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";
    private static final String EX_WALLET4001 =
            "{\"success\":false,\"code\":\"WALLET4001\",\"message\":\"존재하지 않는 지갑입니다.\"}";
    private static final String EX_MEMBER4001 =
            "{\"success\":false,\"code\":\"MEMBER4001\",\"message\":\"존재하지 않는 회원입니다.\"}";
    private static final String EX_ACCOUNT4001 =
            "{\"success\":false,\"code\":\"ACCOUNT4001\",\"message\":\"존재하지 않는 계좌입니다.\"}";
    private static final String EX_COMMON5031 =
            "{\"success\":false,\"code\":\"COMMON5031\",\"message\":\"일시적으로 처리할 수 없습니다.\"}";
    private static final String EX_TRANSFER4002 =
            "{\"success\":false,\"code\":\"TRANSFER4002\",\"message\":\"지원하지 않는 통화입니다.\"}";
    private static final String EX_TRANSFER4003 =
            "{\"success\":false,\"code\":\"TRANSFER4003\",\"message\":\"지원하지 않는 송금 유형입니다.\"}";
    private static final String EX_TRANSFER4004 =
            "{\"success\":false,\"code\":\"TRANSFER4004\",\"message\":\"자기 자신에게 송금할 수 없습니다.\"}";
    private static final String EX_TRANSFER4005 =
            "{\"success\":false,\"code\":\"TRANSFER4005\",\"message\":\"지원하지 않는 통화 조합입니다.\"}";
    private static final String EX_WALLET4002 =
            "{\"success\":false,\"code\":\"WALLET4002\",\"message\":\"지갑 잔액이 부족합니다.\"}";
    private static final String EX_TRANSFER4009 =
            "{\"success\":false,\"code\":\"TRANSFER4009\",\"message\":\"송금 PIN이 설정되지 않았습니다.\"}";
    private static final String EX_TRANSFER4010 =
            "{\"success\":false,\"code\":\"TRANSFER4010\",\"message\":\"송금 전 PIN 검증이 필요합니다.\"}";

    private final TransferService transferService;

    /** 최근 송금 앱 사용자 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "최근 송금 앱 사용자 조회",
            description = "내가 송신자였던 앱 내부 송금(INTERNAL_TRANSFER, COMPLETED) 기록에서 "
                    + "수신자별 가장 최근 송금 1건씩, 최근순으로 최대 10명을 반환한다. "
                    + "사용자는 JWT의 public_id claim으로 식별한다.")
    // responseCode는 HTTP 상태, description에 비즈니스 코드 명시 (잔액 조회 컨트롤러와 동일 규칙).
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 RecentRecipientsResponse가 담긴다."),
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
    @GetMapping("/recent-recipients/members")
    public ApiResponse<RecentRecipientsResponse> getRecentInternalRecipients(
            @CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(transferService.getRecentInternalRecipients(userPublicId));
    }

    /** 이메일로 앱 사용자 유효성 검증. 🔒 JWT 필요. */
    @Operation(
            summary = "앱 사용자 유효성 검증",
            description = "송금 화면에서 수취인 이메일로 앱 사용자가 존재하는지 검증한다. "
                    + "존재하면 receiver_public_id/nickname/is_verified를 반환, 없으면 404 MEMBER4001. "
                    + "이 API는 본인 식별보다 '대상 회원 검증'이 핵심이지만 인증은 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "검증 성공. 응답은 공통 ApiResponse로 감싸지며 data에 ValidateMemberResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 이메일 형식 오류/누락.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "MEMBER4001 - 존재하지 않는 회원(해당 이메일의 앱 사용자 없음).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "MEMBER4001", value = EX_MEMBER4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/validate-member")
    public ApiResponse<ValidateMemberResponse> validateMember(
            @RequestParam @NotBlank @Email String email) {
        return ApiResponse.success(transferService.validateMember(email));
    }

    /** 지원 통화 목록 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "지원 통화 목록 조회",
            description = "송금/환전 화면에서 사용 가능한 통화 4종(KRW/USD/PHP/VND)을 반환한다. "
                    + "CurrencyType enum이 SSOT라 DB/외부 호출 없는 순수 enum 조회. "
                    + "이 API는 본인 식별이 비즈니스에 영향이 없지만 인증은 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 SupportedCurrenciesResponse가 담긴다."),
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
    @GetMapping("/supported-currencies")
    public ApiResponse<SupportedCurrenciesResponse> getSupportedCurrencies() {
        return ApiResponse.success(transferService.getSupportedCurrencies());
    }

    /** 최근 송금 계좌(타행 REMITTANCE) 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "최근 송금 계좌 조회",
            description = "내가 송신자였던 타행 송금(REMITTANCE, COMPLETED) 기록에서 "
                    + "bank_account별 가장 최근 송금 1건씩, 최근순으로 size건(기본 10, 1~50)을 반환한다. "
                    + "wallet 도메인 내부 DB만 조회하며 외부 호출 없음. "
                    + "사용자는 JWT의 public_id claim으로 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 RecentAccountsResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - size 범위 위반(1~50).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
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
    @GetMapping("/recent-accounts")
    public ApiResponse<RecentAccountsResponse> getRecentRemittanceAccounts(
            @CurrentUserPublicId String userPublicId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) Integer size) {
        return ApiResponse.success(transferService.getRecentRemittanceAccounts(userPublicId, size));
    }

    /** 송금 수수료 계산. 🔒 JWT 필요. */
    @Operation(
            summary = "송금 수수료 조회",
            description = "송금 화면에서 입력한 방식·통화·금액으로 수수료를 계산해 반환한다. "
                    + "정책(docs/remittance/api-spec.md §4): INTERNAL_TRANSFER=0, REMITTANCE=amount×0.5%(HALF_UP 4자리). "
                    + "DB·외부 호출 없는 순수 계산. 미지원 통화(KRW/USD/PHP/VND 외)는 TRANSFER4002. "
                    + "사용자는 JWT의 public_id claim으로 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "계산 성공. 응답은 공통 ApiResponse로 감싸지며 data에 TransferFeeResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - Body 검증 실패 / TRANSFER4002 - 미지원 통화 / TRANSFER4003 - 미지원 송금 유형. "
                            + "같은 400이지만 비즈니스 코드가 다르다 (examples 드롭다운 참고).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                    @ExampleObject(name = "COMMON4001", value = EX_COMMON4001),
                                    @ExampleObject(name = "TRANSFER4002", value = EX_TRANSFER4002),
                                    @ExampleObject(name = "TRANSFER4003", value = EX_TRANSFER4003)
                            })),
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
    @PostMapping("/fee")
    public ApiResponse<TransferFeeResponse> getTransferFee(
            @Valid @RequestBody TransferFeeRequest request) {
        return ApiResponse.success(transferService.getTransferFee(request));
    }

    /** 송금 실행(1단계: INTERNAL_TRANSFER + 같은 통화 전용). 🔒 JWT 필요. */
    @Operation(
            summary = "송금 실행",
            description = "INTERNAL_TRANSFER(앱 사용자 간 송금)를 실행한다. 1단계 범위는 같은 통화 송금만 — "
                    + "currency_code != receive_currency_code면 TRANSFER4005. REMITTANCE는 후속 PR에서 추가. "
                    + "Idempotency-Key 헤더로 멱등성 보장(3-layer: Redis 캐시 → DB UNIQUE → race 시 첫 결과 재조회). "
                    + "두 wallet에 대한 분산 락(wallet_id 오름차순 MultiLock) + DB 비관적 락으로 동시성 보호. "
                    + "사용자는 JWT의 public_id claim으로 식별한다. "
                    + "사전 흐름(TX-PIN): POST /transfers/pin-verify(§5) 성공 후 호출 — 서버가 검증 마커를 강제 "
                    + "확인한다. 마커 없으면 TRANSFER4010(428, 미검증)/TRANSFER4009(400, PIN 미설정).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "송금 성공. 응답은 공통 ApiResponse로 감싸지며 data에 TransferExecuteResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - Body 검증 실패 / "
                            + "TRANSFER4002 - 미지원 통화 / TRANSFER4003 - 미지원 송금 유형 / "
                            + "TRANSFER4004 - 자기 송금 / TRANSFER4005 - 미지원 통화 조합 / "
                            + "TRANSFER4009 - 송금 PIN 미설정(TX-PIN). "
                            + "같은 400이지만 비즈니스 코드가 다르다 (examples 드롭다운 참고).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                    @ExampleObject(name = "COMMON4001", value = EX_COMMON4001),
                                    @ExampleObject(name = "TRANSFER4002", value = EX_TRANSFER4002),
                                    @ExampleObject(name = "TRANSFER4003", value = EX_TRANSFER4003),
                                    @ExampleObject(name = "TRANSFER4004", value = EX_TRANSFER4004),
                                    @ExampleObject(name = "TRANSFER4005", value = EX_TRANSFER4005),
                                    @ExampleObject(name = "TRANSFER4009", value = EX_TRANSFER4009)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "WALLET4001 - 존재하지 않는 지갑 (송신자 또는 수신자 지갑·해당 통화 잔액 행 없음).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "WALLET4001", value = EX_WALLET4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "WALLET4002 - 지갑 잔액이 부족합니다 (요청 형식은 정상이나 잔액 부족으로 처리 불가).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "WALLET4002", value = EX_WALLET4002))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "428",
                    description = "TRANSFER4010 - 송금 전 PIN 검증이 필요합니다 (TX-PIN — pin-verify(§5) 성공 마커 없이 직접 호출).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "TRANSFER4010", value = EX_TRANSFER4010))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "COMMON5031 - 분산 락 획득 실패(타임아웃/Redis 일시 장애).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5031", value = EX_COMMON5031)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferExecuteResponse> executeTransfer(
            @CurrentUserPublicId String userPublicId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody TransferExecuteRequest request) {
        TransferExecuteResponse response = transferService.execute(userPublicId, idempotencyKey, request);
        return ApiResponse.success(response, "송금이 완료되었습니다.");
    }

    /** 송금 확인증 조회. 🔒 JWT 필요. 송신자 본인만 조회 가능. */
    @Operation(
            summary = "송금 확인증 조회",
            description = "완료된 송금 한 건의 확인증(송·수취인, 금액, 수수료, 적용 환율, 수취 금액 등)을 조회한다. "
                    + "대상은 INTERNAL_TRANSFER · REMITTANCE만이며, 송신자 본인만 조회 가능하다. "
                    + "본인 아님·미존재·미지원 유형은 정보 누설 방지로 동일한 TRANSFER4001로 모호 매핑한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 TransferReceiptResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(path variable 형식 위반).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "TRANSFER4001 - 존재하지 않는 송금 내역입니다. "
                            + "(미존재·본인 아님·미지원 유형 모두 동일 매핑)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = EX_COMMON5000)))
    })
    @GetMapping("/{transferPublicId}/receipt")
    public ApiResponse<TransferReceiptResponse> getReceipt(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("transferPublicId") @NotBlank @Size(max = 36) String transferPublicId) {
        return ApiResponse.success(transferService.getReceipt(userPublicId, transferPublicId));
    }

    /** 정기 송금 대상 유효성 사전 검증. 🔒 JWT 필요. INTERNAL_TRANSFER · REMITTANCE 둘 다 지원. */
    @Operation(
            summary = "정기 송금 대상 유효성 검증",
            description = "정기 송금 설정 전 (수취 대상, 금액, 통화) 조합이 유효한지 사전 검증한다. "
                    + "도메인 검증(통화 정합성 등) 미통과는 200 + is_valid=false + reason으로 응답되며, "
                    + "입력 형식·계좌 미존재·미인증·자기송금 등은 도메인 에러(400/403/404)로 응답된다. "
                    + "송금 실행 API와 동일하게 transfer_type으로 INTERNAL_TRANSFER/REMITTANCE 분기.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "검증 자체는 성공. is_valid=true면 통과, false면 reason에 사유."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(필수 필드 누락·형식 오류) "
                            + "/ TRANSFER4002 - 지원하지 않는 통화입니다 / TRANSFER4003 - 지원하지 않는 송금 유형입니다 "
                            + "/ TRANSFER4004 - 자기 자신에게 송금할 수 없습니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "ACCOUNT4006 - 인증되지 않은 계좌입니다(REMITTANCE — mock_account_token 미발급).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "ACCOUNT4001 - 존재하지 않는 계좌입니다(REMITTANCE — 본인 + active 미매칭) "
                            + "/ WALLET4001 - 존재하지 않는 지갑입니다(INTERNAL — 수신자 wallet 부재).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = EX_WALLET4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = EX_COMMON5000)))
    })
    @PostMapping("/scheduled/validate")
    public ApiResponse<ValidateScheduledResponse> validateScheduled(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody ValidateScheduledRequest request) {
        return ApiResponse.success(transferService.validateScheduled(userPublicId, request));
    }
}
