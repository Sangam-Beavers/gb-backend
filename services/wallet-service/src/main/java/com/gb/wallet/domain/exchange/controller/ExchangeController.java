package com.gb.wallet.domain.exchange.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.exchange.service.ExchangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Exchange", description = "환전 API")
@RestController
@RequiredArgsConstructor
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
}
