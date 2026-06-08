package com.gb.admin.domain.monitoring.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "대기열 응답")
public record QueuesResponse(
        @Schema(description = "대기열 목록.")
        List<Queue> queues
) {

    @Schema(description = "대기열 단건")
    public record Queue(
            @Schema(description = "대기열 키.", example = "KYC_PENDING",
                    allowableValues = {"KYC_PENDING", "COMMUNITY_REPORTS", "CHARGE_FAILED", "ANALYSIS_FAILED"})
            String name,

            @Schema(description = "라벨.", example = "KYC 대기")
            String label,

            @Schema(description = "건수.", example = "25")
            long count
    ) {
    }
}
