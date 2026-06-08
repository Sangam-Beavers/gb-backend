package com.gb.document.domain.admin.controller;

import com.gb.common.response.ApiResponse;
import com.gb.document.domain.admin.dto.response.AdminDocumentPageResponse;
import com.gb.document.domain.admin.dto.response.DocumentStatsResponse;
import com.gb.document.domain.admin.service.DocumentAdminInternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[Internal] Document Admin", description = "관리자 BFF 전용 document 내부 API")
@RestController
@RequestMapping("/api/v1/internal/admin")
@RequiredArgsConstructor
public class DocumentAdminInternalController {

    private final DocumentAdminInternalService documentAdminInternalService;

    @Operation(summary = "[Internal] 문서 분석 페이지")
    @GetMapping("/documents")
    public ApiResponse<AdminDocumentPageResponse> search(
            @RequestParam(required = false, name = "user_public_id") String userPublicId,
            @RequestParam(required = false, name = "risk_level") String riskLevel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(documentAdminInternalService.search(userPublicId, riskLevel, page, size));
    }

    @Operation(summary = "[Internal] 문서 분석 통계")
    @GetMapping("/documents/stats")
    public ApiResponse<DocumentStatsResponse> stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.success(documentAdminInternalService.stats(from, to));
    }
}
