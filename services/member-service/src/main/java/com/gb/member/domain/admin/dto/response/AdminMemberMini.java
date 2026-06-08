package com.gb.member.domain.admin.dto.response;

import com.gb.member.domain.member.entity.Member;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 BFF 가 user_public_id 묶음으로 N+1 없이 회원 표시 정보를 일괄 조회할 때 쓰는 최소 DTO.
 */
@Schema(description = "회원 최소 표시 정보(관리자 일괄 lookup)")
public record AdminMemberMini(
        String userPublicId,
        String email,
        String nickname,
        String nationality
) {
    public static AdminMemberMini from(Member m) {
        return new AdminMemberMini(m.getPublicId(), m.getEmail(), m.getNickname(), m.getNationality());
    }
}
