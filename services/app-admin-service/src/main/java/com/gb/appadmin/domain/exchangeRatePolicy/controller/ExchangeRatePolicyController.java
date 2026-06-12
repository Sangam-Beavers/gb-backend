package com.gb.appadmin.domain.exchangeRatePolicy.controller;

import com.gb.appadmin.domain.exchangeRatePolicy.dto.response.ExchangeRatePolicyResponse;
import com.gb.appadmin.domain.exchangeRatePolicy.service.ExchangeRatePolicyService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Exchange Rate Policies (Public)", description = "환율 정책 공개 조회")
@RestController
@RequestMapping("/api/v1/app-admin/app/exchange-rate-policies")
@RequiredArgsConstructor
public class ExchangeRatePolicyController {

    private final ExchangeRatePolicyService service;

    @Operation(summary = "환율 정책 전체 목록")
    @GetMapping
    public ApiResponse<List<ExchangeRatePolicyResponse>> listAll() {
        return ApiResponse.success(service.listAll());
    }

    @Operation(summary = "환율 정책 상세")
    @GetMapping("/{publicId}")
    public ApiResponse<ExchangeRatePolicyResponse> getOne(@PathVariable String publicId) {
        return ApiResponse.success(service.getByPublicId(publicId));
    }
}
