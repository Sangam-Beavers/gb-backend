package com.gb.member.domain.member.dto.response;

import com.gb.member.domain.member.entity.Member;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "서류 분석 크레딧 응답")
public record CreditResponse(
        @Schema(description = "회원 public_id", example = "550e8400-e29b-41d4-a716-446655440000")
        String userPublicId,
        @Schema(description = "잔여 서류 분석 크레딧", example = "3")
        int remainingCredit
) {
    public static CreditResponse from(Member member) {
        return new CreditResponse(member.getPublicId(), member.getDocAnalysisCredit());
    }
}
