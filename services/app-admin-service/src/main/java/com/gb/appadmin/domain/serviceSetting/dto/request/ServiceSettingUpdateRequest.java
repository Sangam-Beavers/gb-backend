package com.gb.appadmin.domain.serviceSetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "서비스 설정 수정 요청 (null 필드는 변경 안 함)")
public record ServiceSettingUpdateRequest(
        @Schema(description = "설정 값") String settingValue,
        @Schema(description = "설명") @Size(max = 300) String description,
        @Schema(description = "활성") Boolean active
) {}
