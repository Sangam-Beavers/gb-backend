package com.gb.community.domain.report.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "신고 생성 요청")
public record CreateReportRequest(

        @Schema(description = "신고 사유 (SPAM / ABUSE / FRAUD / SEXUAL / ETC)", example = "SPAM",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason,

        @Size(max = 500)
        @Schema(description = "상세 설명 (선택, 최대 500자)", example = "스팸성 광고 게시글입니다.")
        String detail
) {
}
