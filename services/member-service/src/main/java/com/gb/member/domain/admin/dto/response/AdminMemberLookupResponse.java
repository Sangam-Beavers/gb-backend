package com.gb.member.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "관리자 회원 lookup 응답(요청한 모든 id 가 키로 포함; 미존재는 누락).")
public record AdminMemberLookupResponse(
        Map<String, AdminMemberMini> members
) {
}
