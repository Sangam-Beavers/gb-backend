package com.gb.appadmin.domain.feePolicy.controller;

import com.gb.appadmin.domain.feePolicy.dto.request.FeePolicyCreateRequest;
import com.gb.appadmin.domain.feePolicy.dto.request.FeePolicyUpdateRequest;
import com.gb.appadmin.domain.feePolicy.dto.response.FeePolicyResponse;
import com.gb.appadmin.domain.feePolicy.service.FeePolicyService;
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

@Tag(name = "Fee Policies (Admin)", description = "수수료 정책 관리")
@RestController
@RequestMapping("/api/v1/app-admin/admin/fee-policies")
@RequiredArgsConstructor
public class AdminFeePolicyController {

    private final FeePolicyService feePolicyService;

    @Operation(summary = "수수료 정책 전체 목록(관리자)")
    @GetMapping
    public ApiResponse<List<FeePolicyResponse>> listAll() {
        return ApiResponse.success(feePolicyService.listAll());
    }

    @Operation(summary = "수수료 정책 등록")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FeePolicyResponse> create(@Valid @RequestBody FeePolicyCreateRequest request) {
        return ApiResponse.success(feePolicyService.create(request));
    }

    @Operation(summary = "수수료 정책 수정 (이력 기록)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "ADMIN4004 - 존재하지 않는 수수료 정책입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{publicId}")
    public ApiResponse<FeePolicyResponse> update(@PathVariable String publicId,
                                                 @CurrentAdminPublicId String adminPublicId,
                                                 @Valid @RequestBody FeePolicyUpdateRequest request) {
        return ApiResponse.success(feePolicyService.update(publicId, adminPublicId, request));
    }
}
