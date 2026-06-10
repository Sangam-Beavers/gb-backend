package com.gb.appadmin.domain.serviceSetting.controller;

import com.gb.appadmin.domain.serviceSetting.dto.response.ServiceSettingResponse;
import com.gb.appadmin.domain.serviceSetting.service.ServiceSettingService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Service Settings (Public)", description = "서비스 설정 공개 조회")
@RestController
@RequestMapping("/api/v1/app/settings")
@RequiredArgsConstructor
public class ServiceSettingController {

    private final ServiceSettingService settingService;

    @Operation(summary = "서비스 설정 전체 목록")
    @GetMapping
    public ApiResponse<List<ServiceSettingResponse>> listAll() {
        return ApiResponse.success(settingService.listAll());
    }
}
