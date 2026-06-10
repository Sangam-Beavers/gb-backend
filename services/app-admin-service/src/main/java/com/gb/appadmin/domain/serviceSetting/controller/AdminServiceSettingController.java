package com.gb.appadmin.domain.serviceSetting.controller;

import com.gb.appadmin.domain.serviceSetting.dto.request.ServiceSettingCreateRequest;
import com.gb.appadmin.domain.serviceSetting.dto.request.ServiceSettingUpdateRequest;
import com.gb.appadmin.domain.serviceSetting.dto.response.ServiceSettingResponse;
import com.gb.appadmin.domain.serviceSetting.service.ServiceSettingService;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Service Settings (Admin)", description = "서비스 설정 관리")
@RestController
@RequestMapping("/api/v1/admin/app/settings")
@RequiredArgsConstructor
public class AdminServiceSettingController {

    private final ServiceSettingService settingService;

    @Operation(summary = "서비스 설정 전체 목록(관리자)")
    @GetMapping
    public ApiResponse<List<ServiceSettingResponse>> listAll() {
        return ApiResponse.success(settingService.listAll());
    }

    @Operation(summary = "서비스 설정 등록")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ServiceSettingResponse> create(@Valid @RequestBody ServiceSettingCreateRequest request) {
        return ApiResponse.success(settingService.create(request));
    }

    @Operation(summary = "서비스 설정 수정")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "ADMIN4006 - 존재하지 않는 서비스 설정입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{publicId}")
    public ApiResponse<ServiceSettingResponse> update(@PathVariable String publicId,
                                                      @Valid @RequestBody ServiceSettingUpdateRequest request) {
        return ApiResponse.success(settingService.update(publicId, request));
    }
}
