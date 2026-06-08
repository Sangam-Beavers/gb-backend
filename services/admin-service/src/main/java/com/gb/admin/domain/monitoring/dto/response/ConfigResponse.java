package com.gb.admin.domain.monitoring.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "운영 설정 노출 응답")
public record ConfigResponse(
        @Schema(description = "운영 설정 목록.")
        List<Config> configs
) {

    @Schema(description = "운영 설정 단건")
    public record Config(
            @Schema(description = "설정 키.", example = "wallet.charge.single-limit")
            String key,

            @Schema(description = "설정 값(String).", example = "10000000")
            String value,

            // currency가 null인 항목도 응답에 포함되도록 ALWAYS 직렬화.
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "통화(KRW 등) — 통화 무관 설정은 null.",
                    example = "KRW", nullable = true)
            String currency
    ) {
    }
}
