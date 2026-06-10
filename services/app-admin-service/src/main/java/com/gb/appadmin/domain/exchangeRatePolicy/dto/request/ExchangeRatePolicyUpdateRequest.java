package com.gb.appadmin.domain.exchangeRatePolicy.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

@Schema(description = "환율 정책 수정 요청 (null 필드는 변경 안 함)")
public record ExchangeRatePolicyUpdateRequest(
        @Schema(description = "스프레드(%)") @DecimalMin("0") BigDecimal spread,
        @Schema(description = "활성 여부") Boolean active
) {}
