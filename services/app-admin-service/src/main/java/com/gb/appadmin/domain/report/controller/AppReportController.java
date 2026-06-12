package com.gb.appadmin.domain.report.controller;

import com.gb.appadmin.domain.report.dto.response.MemberReportsResponse;
import com.gb.appadmin.domain.report.dto.response.ReportedAuthorPageResponse;
import com.gb.appadmin.domain.report.service.AppReportService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "App Admin - 신고 관리", description = "앱 관리자용 신고된 회원 조회")
@RestController
@RequestMapping("/api/v1/admin/app")
@RequiredArgsConstructor
public class AppReportController {

    private final AppReportService appReportService;

    @Operation(summary = "신고당한 회원 목록 (신고수 내림차순)")
    @GetMapping("/reports")
    public ApiResponse<ReportedAuthorPageResponse> getReportedAuthors(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(appReportService.getReportedAuthors(page, size));
    }

    @Operation(summary = "특정 회원이 받은 신고 상세 목록")
    @GetMapping("/members/{userPublicId}/reports")
    public ApiResponse<MemberReportsResponse> getMemberReports(
            @PathVariable String userPublicId) {
        return ApiResponse.success(appReportService.getMemberReports(userPublicId));
    }
}
