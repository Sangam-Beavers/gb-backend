package com.gb.appadmin.domain.feePolicy.dto.response;

import com.gb.appadmin.domain.feePolicy.entity.FeePolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "수수료 정책 응답")
public record FeePolicyResponse(
        @Schema(description = "public_id") String publicId,
        @Schema(description = "서비스 타입") String serviceType,
        @Schema(description = "수수료 타입") String feeType,
        @Schema(description = "수수료 값(string)", example = "1000.0000") String feeValue,
        @Schema(description = "최소 수수료(string, nullable)") String minFee,
        @Schema(description = "최대 수수료(string, nullable)") String maxFee,
        @Schema(description = "통화") String currency,
        @Schema(description = "활성") Boolean active,
        @Schema(description = "수정 시각(UTC)") LocalDateTime updatedAt
) {
    public static FeePolicyResponse from(FeePolicy p) {
        return new FeePolicyResponse(
                p.getPublicId(),
                p.getServiceType(),
                p.getFeeType(),
                p.getFeeValue().toPlainString(),
                p.getMinFee() != null ? p.getMinFee().toPlainString() : null,
                p.getMaxFee() != null ? p.getMaxFee().toPlainString() : null,
                p.getCurrency(),
                p.getActive(),
                p.getUpdatedAt()
        );
    }
}
