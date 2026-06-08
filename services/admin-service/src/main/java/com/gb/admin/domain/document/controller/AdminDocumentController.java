package com.gb.admin.domain.document.controller;

import com.gb.admin.domain.document.dto.response.AdminDocumentPageResponse;
import com.gb.admin.domain.document.dto.response.AdminDocumentStatsResponse;
import com.gb.admin.domain.document.service.AdminDocumentService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Documents", description = "AI 문서 분석 통계/최근 분석")
@RestController
@RequestMapping("/api/v1/admin/documents")
@RequiredArgsConstructor
public class AdminDocumentController {

    private final AdminDocumentService adminDocumentService;

    @Operation(summary = "문서 분석 통계", description = "Phase 1 Mock — 다음 스프린트에서 document-service /internal/admin 연결.")
    @GetMapping("/stats")
    public ApiResponse<AdminDocumentStatsResponse> stats() {
        return ApiResponse.success(adminDocumentService.stats());
    }

    @Operation(summary = "최근 분석 문서 목록")
    @GetMapping("/recent")
    public ApiResponse<AdminDocumentPageResponse> recent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminDocumentService.recent(page, size));
    }
}
