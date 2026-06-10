package com.gb.appadmin.domain.feePolicy.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

@Schema(description = "수수료 정책 수정 요청 (null 필드는 변경 안 함)")
public record FeePolicyUpdateRequest(
        @Schema(description = "수수료 타입(FIXED/PERCENT)") String feeType,
        @Schema(description = "수수료 값") @DecimalMin("0") BigDecimal feeValue,
        @Schema(description = "최소 수수료") BigDecimal minFee,
        @Schema(description = "최대 수수료") BigDecimal maxFee,
        @Schema(description = "활성 여부") Boolean active
) {}
