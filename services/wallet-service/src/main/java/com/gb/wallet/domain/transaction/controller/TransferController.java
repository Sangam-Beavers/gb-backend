package com.gb.wallet.domain.transaction.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.domain.transaction.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private static final String EX_COMMON4011 =
            "{\"success\":false,\"code\":\"COMMON4011\",\"message\":\"인증 정보가 유효하지 않습니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";
    private static final String EX_WALLET4001 =
            "{\"success\":false,\"code\":\"WALLET4001\",\"message\":\"존재하지 않는 지갑입니다.\"}";
    private static final String EX_MEMBER4001 =
            "{\"success\":false,\"code\":\"MEMBER4001\",\"message\":\"존재하지 않는 회원입니다.\"}";

    private final TransferService transferService;

    /** 최근 송금 앱 사용자 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "최근 송금 앱 사용자 조회",
            description = "내가 송신자였던 앱 내부 송금(INTERNAL_TRANSFER, COMPLETED) 기록에서 "
                    + "수신자별 가장 최근 송금 1건씩, 최근순으로 최대 10명을 반환한다. "
                    + "인증 미구현 상태라 현재는 X-User-Public-Id 헤더로 사용자를 식별한다.")
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
            // TODO: 인증 구현 후 JWT 토큰(sub/claim)에서 userPublicId를 추출하도록 교체.
            //       현재는 인증 미구현으로 헤더(X-User-Public-Id)로 임시 수신.
            @RequestHeader("X-User-Public-Id") String userPublicId) {
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
                    description = "COMMON4011 - 인증 정보가 유효하지 않습니다. (인증 구현 후 활성화)",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4011", value = EX_COMMON4011))),
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
            // TODO: 인증 구현 후 JWT로 교체. 현재는 임시 헤더.
            @RequestHeader("X-User-Public-Id") String userPublicId,
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
                    description = "COMMON4011 - 인증 정보가 유효하지 않습니다. (인증 구현 후 활성화)",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4011", value = EX_COMMON4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/supported-currencies")
    public ApiResponse<SupportedCurrenciesResponse> getSupportedCurrencies(
            // TODO: 인증 구현 후 JWT로 교체. 현재는 임시 헤더 (이 API는 본인 식별을 쓰지 않지만 인증 API라 헤더는 받아둠).
            @RequestHeader("X-User-Public-Id") String userPublicId) {
        return ApiResponse.success(transferService.getSupportedCurrencies());
    }

    /** 최근 송금 계좌(타행 REMITTANCE) 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "최근 송금 계좌 조회",
            description = "내가 송신자였던 타행 송금(REMITTANCE, COMPLETED) 기록에서 "
                    + "bank_account별 가장 최근 송금 1건씩, 최근순으로 size건(기본 10, 1~50)을 반환한다. "
                    + "wallet 도메인 내부 DB만 조회하며 외부 호출 없음. "
                    + "인증 미구현 상태라 현재는 X-User-Public-Id 헤더로 사용자를 식별한다.")
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
                    description = "COMMON4011 - 인증 정보가 유효하지 않습니다. (인증 구현 후 활성화)",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4011", value = EX_COMMON4011))),
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
            // TODO: 인증 구현 후 JWT로 교체. 현재는 임시 헤더.
            @RequestHeader("X-User-Public-Id") String userPublicId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) Integer size) {
        return ApiResponse.success(transferService.getRecentRemittanceAccounts(userPublicId, size));
    }
}
