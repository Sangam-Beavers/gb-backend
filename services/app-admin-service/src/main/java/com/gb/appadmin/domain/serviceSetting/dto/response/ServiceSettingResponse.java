package com.gb.appadmin.domain.serviceSetting.dto.response;

import com.gb.appadmin.domain.serviceSetting.entity.ServiceSetting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "서비스 설정 응답")
public record ServiceSettingResponse(
        @Schema(description = "public_id") String publicId,
        @Schema(description = "설정 키", example = "MAINTENANCE_MODE") String settingKey,
        @Schema(description = "설정 값", example = "false") String settingValue,
        @Schema(description = "설명") String description,
        @Schema(description = "활성") Boolean active,
        @Schema(description = "수정 시각(UTC)") LocalDateTime updatedAt
) {
    public static ServiceSettingResponse from(ServiceSetting s) {
        return new ServiceSettingResponse(
                s.getPublicId(),
                s.getSettingKey(),
                s.getSettingValue(),
                s.getDescription(),
                s.getActive(),
                s.getUpdatedAt()
        );
    }
}
