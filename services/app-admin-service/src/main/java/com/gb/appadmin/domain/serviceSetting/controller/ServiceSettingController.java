package com.gb.appadmin.domain.serviceSetting.controller;

import com.gb.appadmin.domain.serviceSetting.dto.response.ServiceSettingResponse;
import com.gb.appadmin.domain.serviceSetting.service.ServiceSettingService;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Service Settings (Public)", description = "서비스 설정 공개 조회")
@RestController
@RequestMapping("/api/v1/app-admin/app/settings")
@RequiredArgsConstructor
public class ServiceSettingController {

    private final ServiceSettingService settingService;

    @Operation(summary = "서비스 설정 전체 목록")
    @GetMapping
    public ApiResponse<List<ServiceSettingResponse>> listAll() {
        return ApiResponse.success(settingService.listAll());
    }

    @Operation(summary = "신규 가입자 서류 분석 기본 크레딧 조회",
            description = "member-service가 회원가입 시 초기 크레딧 값을 읽기 위한 공개 엔드포인트. setting_key = DOC_ANALYSIS_CREDIT")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "ADMIN4006 - 존재하지 않는 서비스 설정입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/doc-analysis-credit")
    public ApiResponse<ServiceSettingResponse> getDocAnalysisCredit() {
        return ApiResponse.success(settingService.getBySettingKey("DOC_ANALYSIS_CREDIT"));
    }
}
