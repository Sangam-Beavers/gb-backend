package com.gb.wallet.domain.admin.controller;

import com.gb.common.response.ApiResponse;
import com.gb.wallet.domain.admin.dto.response.AdminTransactionPageResponse;
import com.gb.wallet.domain.admin.dto.response.ChargeAttemptPageResponse;
import com.gb.wallet.domain.admin.dto.response.TransactionAuditLogPageResponse;
import com.gb.wallet.domain.admin.dto.response.TransactionAuditTrailResponse;
import com.gb.wallet.domain.admin.dto.response.TransactionStatsResponse;
import com.gb.wallet.domain.admin.service.WalletAdminInternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * wallet-service 의 관리자 BFF 전용 내부 API.
 *
 * <p>경로 {@code /api/v1/internal/admin/*} 는 SecurityConfig 에서 {@code permitAll} 처리된다
 * (다음 스프린트에 mTLS·NetworkPolicy 로 격리). admin-service 가 JWT 없이 호출한다.
 *
 * <p>일반 사용자 API({@code /api/v1/transactions/*})와 분리해 별도 컨트롤러로 둔다 — 정책 변경 시
 * 일반 경로 영향 0.
 */
@Tag(name = "[Internal] Wallet Admin", description = "관리자 BFF 전용 wallet 내부 API")
@RestController
@RequestMapping("/api/v1/internal/admin")
@RequiredArgsConstructor
public class AdminInternalController {

    private final WalletAdminInternalService walletAdminInternalService;

    @Operation(summary = "[Internal] 거래 검색")
    @GetMapping("/transactions")
    public ApiResponse<AdminTransactionPageResponse> searchTransactions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String risk,
            @RequestParam(required = false, name = "user_public_id") String userPublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(walletAdminInternalService.searchTransactions(
                from, to, type, status, risk, userPublicId, page, size));
    }

    @Operation(summary = "[Internal] 거래 1건 + 감사 로그 시간순")
    @GetMapping("/transactions/{publicId}/audit-logs")
    public ApiResponse<TransactionAuditTrailResponse> getAuditTrail(@PathVariable String publicId) {
        return ApiResponse.success(walletAdminInternalService.getAuditTrail(publicId));
    }

    @Operation(summary = "[Internal] 금융 감사 로그 검색")
    @GetMapping("/transaction-audit-logs")
    public ApiResponse<TransactionAuditLogPageResponse> searchAuditLogs(
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
        return ApiResponse.success(walletAdminInternalService.searchAuditLogs(
                userPublicId, action, status, from, to, minAmount, maxAmount, ipAddress, page, size));
    }

    @Operation(summary = "[Internal] 충전 실패 큐 조회")
    @GetMapping("/charge-attempts")
    public ApiResponse<ChargeAttemptPageResponse> searchChargeAttempts(
            @RequestParam(required = false, defaultValue = "FAILED") String status,
            @RequestParam(required = false, name = "user_public_id") String userPublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(walletAdminInternalService.searchChargeAttempts(
                status, userPublicId, page, size));
    }

    @Operation(summary = "[Internal] 거래 통계")
    @GetMapping("/stats/transactions")
    public ApiResponse<TransactionStatsResponse> getStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.success(walletAdminInternalService.getStats(from, to));
    }
}
