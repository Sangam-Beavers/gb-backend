package com.gb.member.domain.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "관리자 KYC 거절 요청")
public record KycRejectAdminRequest(
        @NotBlank @Size(max = 255)
        @Schema(description = "거절 사유.", example = "신분증 사진이 흐립니다.")
        String reason
) {
}
