package com.gb.appadmin.domain.exchangeRatePolicy.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(description = "환율 정책 등록 요청")
public record ExchangeRatePolicyCreateRequest(
        @Schema(description = "통화 코드", example = "PHP")
        @NotBlank String currencyCode,

        @Schema(description = "스프레드(%)", example = "1.5000")
        @NotNull @DecimalMin("0") BigDecimal spread,

        @Schema(description = "활성 여부", example = "true")
        Boolean active
) {}
