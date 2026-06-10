package com.gb.appadmin.domain.feePolicy.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(description = "수수료 정책 등록 요청")
public record FeePolicyCreateRequest(
        @Schema(description = "서비스 타입(TRANSFER/EXCHANGE/CHARGE/CASHOUT)", example = "TRANSFER")
        @NotBlank String serviceType,

        @Schema(description = "수수료 타입(FIXED/PERCENT)", example = "FIXED")
        @NotBlank String feeType,

        @Schema(description = "수수료 값(FIXED=금액, PERCENT=비율)", example = "1000.0000")
        @NotNull @DecimalMin("0") BigDecimal feeValue,

        @Schema(description = "최소 수수료(PERCENT 전용, nullable)", example = "500.0000")
        BigDecimal minFee,

        @Schema(description = "최대 수수료(PERCENT 전용, nullable)", example = "5000.0000")
        BigDecimal maxFee,

        @Schema(description = "통화 코드", example = "KRW")
        @NotBlank String currency,

        @Schema(description = "활성 여부", example = "true")
        Boolean active
) {}
