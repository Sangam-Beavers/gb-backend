package com.gb.admin.domain.monitoring.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "인증 실패 카운터 응답")
public record AuthFailuresResponse(
        @Schema(description = "윈도 크기(분).", example = "5")
        int windowMinutes,

        @Schema(description = "윈도 내 전체 실패 수.", example = "0")
        long totalFailures,

        @Schema(description = "사유별 분포(Phase 1은 비어 있음 — 자체 카운터는 다음 스프린트).")
        List<ByReason> byReason
) {

    @Schema(description = "사유별 카운트")
    public record ByReason(
            @Schema(description = "실패 사유.", example = "TOKEN_EXPIRED")
            String reason,

            @Schema(description = "건수.", example = "0")
            long count
    ) {
    }
}
