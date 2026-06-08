package com.gb.admin.domain.monitoring.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "서비스 헬스체크 응답(member/wallet/document/community)")
public record ServiceHealthResponse(
        @Schema(description = "서비스별 헬스 상태 목록.")
        List<ServiceHealth> services
) {

    @Schema(description = "서비스 단건 헬스")
    public record ServiceHealth(
            @Schema(description = "서비스명.", example = "wallet-service")
            String name,

            @Schema(description = "상태(UP/DOWN/UNKNOWN).", example = "UP",
                    allowableValues = {"UP", "DOWN", "UNKNOWN"})
            String status,

            // 호출 실패/타임아웃 시 null이 응답에 포함되도록 ALWAYS 직렬화.
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "응답 시간(ms). 호출 실패 시 null.", example = "18", nullable = true)
            Long responseTimeMs
    ) {
    }
}
