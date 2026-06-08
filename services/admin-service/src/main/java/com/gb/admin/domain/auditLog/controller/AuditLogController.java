package com.gb.admin.domain.auditLog.controller;

import com.gb.admin.domain.auditLog.dto.response.AuditLogPageResponse;
import com.gb.admin.domain.auditLog.service.AuditLogService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Audit Logs", description = "관리자 감사 로그")
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "감사 로그 검색",
            description = "행위/대상 타입/시각 범위로 필터링. 정렬은 created_at desc 고정(Phase 1).")
    @GetMapping
    public ApiResponse<AuditLogPageResponse> search(
            @Parameter(description = "행위(KYC_APPROVE 등)") @RequestParam(required = false) String action,
            @Parameter(description = "대상 타입(MEMBER/POST 등)") @RequestParam(required = false, name = "target_type") String targetType,
            @Parameter(description = "시작 시각(UTC, ISO 8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "종료 시각(UTC, ISO 8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "페이지(0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(auditLogService.search(action, targetType, from, to, page, size));
    }
}
