package com.gb.admin.domain.financialAudit.controller;

import com.gb.admin.domain.financialAudit.dto.response.AdminChargeAttemptPageResponse;
import com.gb.admin.domain.financialAudit.dto.response.FinancialAuditLogPageResponse;
import com.gb.admin.domain.financialAudit.dto.response.MemberLookupResponse;
import com.gb.admin.domain.financialAudit.dto.response.TransactionAuditTrailResponse;
import com.gb.admin.domain.financialAudit.service.FinancialAuditService;
import com.gb.admin.global.client.AuditLogFilter;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Financial Audit", description = "금융 감사 로그/충전 실패 큐/회원 lookup(발표 핵심)")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class FinancialAuditController {

    private final FinancialAuditService financialAuditService;

    @Operation(summary = "금융 감사 로그 검색 (회원 표시 정보 enrich 포함)")
    @GetMapping("/financial-audit-logs")
    public ApiResponse<FinancialAuditLogPageResponse> searchAuditLogs(
            @RequestParam(required = false, name = "user_public_id") String userPublicId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false, name = "min_amount") BigDecimal minAmount,
            @RequestParam(required = false, name = "max_amount") BigDecimal maxAmount,
            @RequestParam(required = false, name = "ip_address") String ipAddress,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuditLogFilter filter = new AuditLogFilter(userPublicId, action, status, from, to,
                minAmount, maxAmount, ipAddress);
        return ApiResponse.success(financialAuditService.searchAuditLogs(filter, page, size));
    }

    @Operation(summary = "거래 1건 + 감사 로그 시간순(드릴다운)")
    @GetMapping("/transactions/{publicId}/audit-logs")
    public ApiResponse<TransactionAuditTrailResponse> getAuditTrail(@PathVariable String publicId) {
        return ApiResponse.success(financialAuditService.getAuditTrail(publicId));
    }

    @Operation(summary = "충전 실패 큐 조회")
    @GetMapping("/charge-attempts")
    public ApiResponse<AdminChargeAttemptPageResponse> chargeAttempts(
            @RequestParam(required = false, defaultValue = "FAILED") String status,
            @RequestParam(required = false, name = "user_public_id") String userPublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(financialAuditService.searchChargeAttempts(status, userPublicId, page, size));
    }

    @Operation(summary = "회원 일괄 lookup(N+1 방지)")
    @GetMapping("/members/lookup")
    public ApiResponse<MemberLookupResponse> lookup(
            @RequestParam(name = "user_public_ids") String userPublicIds) {
        List<String> ids = userPublicIds == null || userPublicIds.isBlank()
                ? List.of()
                : Arrays.stream(userPublicIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).limit(100).toList();
        return ApiResponse.success(financialAuditService.lookup(ids));
    }
}
