package com.gb.admin.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "KYC 거절 요청")
public record KycRejectRequest(
        @NotBlank @Size(max = 500)
        @Schema(description = "거절 사유.", example = "신분증 사진이 흐려 식별 불가")
        String reason
) {
}
