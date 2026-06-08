package com.gb.admin.domain.financialAudit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "회원 일괄 lookup(요청한 모든 id 가 키, 미존재는 누락).")
public record MemberLookupResponse(
        Map<String, MemberMiniResponse> members
) {

    @Schema(description = "회원 최소 표시 정보.")
    public record MemberMiniResponse(
            String userPublicId,
            String email,
            String nickname,
            String nationality
    ) {
    }
}
