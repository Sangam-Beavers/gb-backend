package com.gb.appadmin.domain.feePolicy.controller;

import com.gb.appadmin.domain.feePolicy.dto.response.FeePolicyResponse;
import com.gb.appadmin.domain.feePolicy.service.FeePolicyService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Fee Policies (Public)", description = "수수료 정책 공개 조회")
@RestController
@RequestMapping("/api/v1/app-admin/app/fee-policies")
@RequiredArgsConstructor
public class FeePolicyController {

    private final FeePolicyService feePolicyService;

    @Operation(summary = "수수료 정책 전체 목록")
    @GetMapping
    public ApiResponse<List<FeePolicyResponse>> listAll() {
        return ApiResponse.success(feePolicyService.listAll());
    }

    @Operation(summary = "수수료 정책 상세")
    @GetMapping("/{publicId}")
    public ApiResponse<FeePolicyResponse> getOne(@PathVariable String publicId) {
        return ApiResponse.success(feePolicyService.getByPublicId(publicId));
    }
}
