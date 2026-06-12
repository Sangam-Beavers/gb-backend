package com.gb.appadmin.domain.exchangeRatePolicy.controller;

import com.gb.appadmin.domain.exchangeRatePolicy.dto.request.ExchangeRatePolicyCreateRequest;
import com.gb.appadmin.domain.exchangeRatePolicy.dto.request.ExchangeRatePolicyUpdateRequest;
import com.gb.appadmin.domain.exchangeRatePolicy.dto.response.ExchangeRatePolicyResponse;
import com.gb.appadmin.domain.exchangeRatePolicy.service.ExchangeRatePolicyService;
import com.gb.appadmin.global.security.CurrentAdminPublicId;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Exchange Rate Policies (Admin)", description = "환율 정책 관리")
@RestController
@RequestMapping("/api/v1/app-admin/admin/exchange-rate-policies")
@RequiredArgsConstructor
public class AdminExchangeRatePolicyController {

    private final ExchangeRatePolicyService service;

    @Operation(summary = "환율 정책 전체 목록(관리자)")
    @GetMapping
    public ApiResponse<List<ExchangeRatePolicyResponse>> listAll() {
        return ApiResponse.success(service.listAll());
    }

    @Operation(summary = "환율 정책 등록")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExchangeRatePolicyResponse> create(
            @Valid @RequestBody ExchangeRatePolicyCreateRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @Operation(summary = "환율 정책 수정 (이력 기록)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "ADMIN4005 - 존재하지 않는 환율 정책입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{publicId}")
    public ApiResponse<ExchangeRatePolicyResponse> update(
            @PathVariable String publicId,
            @CurrentAdminPublicId String adminPublicId,
            @Valid @RequestBody ExchangeRatePolicyUpdateRequest request) {
        return ApiResponse.success(service.update(publicId, adminPublicId, request));
    }
}
