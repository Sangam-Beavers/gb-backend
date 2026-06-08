package com.gb.admin.domain.monitoring.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "도메인 SLO 응답")
public record DomainSloResponse(
        @Schema(description = "SLO 목록.")
        List<Slo> slos
) {

    @Schema(description = "SLO 단건")
    public record Slo(
            @Schema(description = "SLO 키.", example = "REMITTANCE_SUCCESS_RATE")
            String name,

            @Schema(description = "표시 라벨.", example = "송금 성공률")
            String label,

            // 비율(%)도 표시 전용 수치라 conventions §0 예외에 해당하지만, 명세에 따라 표기 일관성 위해 String 유지.
            @Schema(description = "목표값(String 십진수).", example = "99.5")
            String target,

            @Schema(description = "현재값(String 십진수).", example = "99.71")
            String current,

            @Schema(description = "에러 버짓 잔여(%, String).", example = "88.0")
            String errorBudgetRemainingPct,

            @Schema(description = "단위.", example = "PERCENT",
                    allowableValues = {"PERCENT", "COUNT", "MILLISECONDS"})
            String unit
    ) {
    }
}
