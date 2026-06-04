package com.gb.wallet.domain.exchange.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.exchange.dto.request.ExchangeExecuteRequest;
import com.gb.wallet.domain.exchange.dto.request.QuoteRequest;
import com.gb.wallet.domain.exchange.dto.response.ExchangeListResponse;
import com.gb.wallet.domain.exchange.dto.response.ExchangeResponse;
import com.gb.wallet.domain.exchange.dto.response.QuoteResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.exchange.service.ExchangeService;
import com.gb.wallet.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

@Tag(name = "Exchange", description = "환전 API")
@RestController
@RequiredArgsConstructor
@Validated // @RequestParam page/size의 @Min/@Max 검증을 활성화한다.
@RequestMapping("/api/v1/exchanges")
public class ExchangeController {

    private final ExchangeService exchangeService;

    @GetMapping("/supported-currencies")
    @Operation(
            summary = "지원 (재)환전 통화 목록 조회",
            description = "환전·재환전에서 사용 가능한 지원 통화 목록을 조회한다. (통화 코드/이름/기호)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data.currencies 로 지원 통화 목록 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<SupportedCurrenciesResponse> getSupportedCurrencies() {
        return ApiResponse.success(exchangeService.getSupportedCurrencies());
    }

    @PostMapping("/quote")
    @Operation(
            summary = "실시간 (재)환전 견적 조회 및 검증",
            description = "환전/재환전의 실시간 환율과 예상 수령액을 계산해 견적을 발급한다(5분간 유효). "
                    + "발급된 quote_public_id로 환전을 실행한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "견적 발급 성공."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "TRANSFER4002 - 지원하지 않는 통화입니다. / COMMON4001 - 요청 값 오류.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<QuoteResponse> createQuote(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody QuoteRequest request) {
        return ApiResponse.success(exchangeService.createQuote(userPublicId, request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "(재)환전 실행",
            description = "견적 식별자(quote_public_id)로 환전을 실행하고 지갑 잔액을 갱신한다. "
                    + "Idempotency-Key 헤더로 멱등성을 보장한다(동일 키 재요청 시 첫 결과 재반환).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "환전 완료."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "EXCHANGE4002 - 환율 견적이 만료되었습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON4031 - 접근 권한이 없습니다 (타인 견적으로 실행 시도).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "EXCHANGE4001 - 존재하지 않는 환전 내역입니다. "
                            + "(타인/타 유형의 idempotency_key 재사용 시 멱등 재반환을 차단 — 존재 미노출)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "WALLET4002 - 지갑 잔액이 부족합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<ExchangeResponse> execute(
            @CurrentUserPublicId String userPublicId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ExchangeExecuteRequest request) {
        return ApiResponse.success(exchangeService.execute(userPublicId, idempotencyKey, request));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "(재)환전 완료 내역 조회",
            description = "환전 내역 식별자(public_id)로 특정 환전 완료 내역의 상세를 조회한다(본인 것만).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON4031 - 접근 권한이 없습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "EXCHANGE4001 - 존재하지 않는 환전 내역입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<ExchangeResponse> getExchange(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") String exchangePublicId) {
        return ApiResponse.success(exchangeService.getExchange(userPublicId, exchangePublicId));
    }

    @GetMapping
    @Operation(
            summary = "(재)환전 내역 목록 조회",
            description = "본인의 환전·재환전 완료 내역을 최근순으로 페이지 조회한다. "
                    + "data.exchanges 배열 + 페이지 메타(page/size/total_elements/total_pages).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "COMMON4001 - page/size 범위 위반.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<ExchangeListResponse> getExchanges(
            @CurrentUserPublicId String userPublicId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(exchangeService.getExchanges(userPublicId, page, size));
    }
}
