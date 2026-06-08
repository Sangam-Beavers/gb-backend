package com.gb.admin.domain.monitoring.controller;

import com.gb.admin.domain.monitoring.dto.response.AuthFailuresResponse;
import com.gb.admin.domain.monitoring.dto.response.ConfigResponse;
import com.gb.admin.domain.monitoring.dto.response.DomainSloResponse;
import com.gb.admin.domain.monitoring.dto.response.EmbedsResponse;
import com.gb.admin.domain.monitoring.dto.response.QueuesResponse;
import com.gb.admin.domain.monitoring.dto.response.ServiceHealthResponse;
import com.gb.admin.domain.monitoring.service.MonitoringService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Monitoring", description = "모니터링 — 서비스 헬스/SLO/대기열/임베드")
@RestController
@RequestMapping("/api/v1/admin/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;

    @Operation(summary = "서비스 헬스체크",
            description = "4개 본체 서비스(/actuator/health) 호출. timeout 1초, 실패는 DOWN.")
    @GetMapping("/service-health")
    public ApiResponse<ServiceHealthResponse> serviceHealth() {
        return ApiResponse.success(monitoringService.serviceHealth());
    }

    @Operation(summary = "도메인 SLO", description = "Phase 1 mock — 송금/AI 분석/KYC/충전 성공률.")
    @GetMapping("/domain-slo")
    public ApiResponse<DomainSloResponse> domainSlo() {
        return ApiResponse.success(monitoringService.domainSlo());
    }

    @Operation(summary = "대기열 카운트", description = "Phase 1 mock — KYC/신고/충전·분석 실패 큐.")
    @GetMapping("/queues")
    public ApiResponse<QueuesResponse> queues() {
        return ApiResponse.success(monitoringService.queues());
    }

    @Operation(summary = "인증 실패 카운터",
            description = "Phase 1 mock — 자체 카운터는 다음 스프린트(window_minutes·total만 노출).")
    @GetMapping("/auth-failures")
    public ApiResponse<AuthFailuresResponse> authFailures() {
        return ApiResponse.success(monitoringService.authFailures());
    }

    @Operation(summary = "운영 설정 노출",
            description = "wallet 정책 mirror — admin-service yml에서 읽는다(다음 스프린트에 wallet과 동기화).")
    @GetMapping("/config")
    public ApiResponse<ConfigResponse> config() {
        return ApiResponse.success(monitoringService.config());
    }

    @Operation(summary = "Phase 2 임베드 URL",
            description = "Grafana 대시보드 5종 + ArgoCD 임베드(placeholder).")
    @GetMapping("/embeds")
    public ApiResponse<EmbedsResponse> embeds() {
        return ApiResponse.success(monitoringService.embeds());
    }
}
