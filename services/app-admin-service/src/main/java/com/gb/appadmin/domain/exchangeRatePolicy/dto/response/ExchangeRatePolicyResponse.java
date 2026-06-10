package com.gb.appadmin.domain.exchangeRatePolicy.dto.response;

import com.gb.appadmin.domain.exchangeRatePolicy.entity.ExchangeRatePolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "환율 정책 응답")
public record ExchangeRatePolicyResponse(
        @Schema(description = "public_id") String publicId,
        @Schema(description = "통화 코드", example = "PHP") String currencyCode,
        @Schema(description = "스프레드(%)(string)", example = "1.5000") String spread,
        @Schema(description = "활성") Boolean active,
        @Schema(description = "수정 시각(UTC)") LocalDateTime updatedAt
) {
    public static ExchangeRatePolicyResponse from(ExchangeRatePolicy p) {
        return new ExchangeRatePolicyResponse(
                p.getPublicId(),
                p.getCurrencyCode(),
                p.getSpread().toPlainString(),
                p.getActive(),
                p.getUpdatedAt()
        );
    }
}
