package com.gb.admin.domain.dashboard.controller;

import com.gb.admin.domain.dashboard.dto.response.DashboardAlertsResponse;
import com.gb.admin.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.gb.admin.domain.dashboard.service.DashboardService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dashboard", description = "관리자 대시보드 요약/알림")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "대시보드 요약",
            description = "오늘 거래 합계(통화별)·DAU·문서 분석 수·대기열 카운트. Phase 1 mock.")
    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryResponse> getSummary() {
        return ApiResponse.success(dashboardService.getSummary());
    }

    @Operation(summary = "대시보드 알림",
            description = "이상거래/KYC/커뮤니티 등 즉시 처리 필요한 알림 목록. Phase 1 mock.")
    @GetMapping("/alerts")
    public ApiResponse<DashboardAlertsResponse> getAlerts() {
        return ApiResponse.success(dashboardService.getAlerts());
    }
}
