package com.gb.appadmin.domain.serviceSetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "서비스 설정 등록 요청")
public record ServiceSettingCreateRequest(
        @Schema(description = "설정 키", example = "MAINTENANCE_MODE")
        @NotBlank @Size(max = 100) String settingKey,

        @Schema(description = "설정 값", example = "false")
        @NotBlank String settingValue,

        @Schema(description = "설정 설명", example = "서비스 점검 모드 활성화 여부")
        @Size(max = 300) String description,

        @Schema(description = "활성 여부", example = "true")
        Boolean active
) {}
