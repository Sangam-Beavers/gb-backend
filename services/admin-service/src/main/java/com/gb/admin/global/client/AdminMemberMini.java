package com.gb.admin.global.client;

/** 회원 표시 정보 최소 셋(audit log enrichment 용 — N+1 방지 lookup 결과). */
public record AdminMemberMini(
        String userPublicId,
        String email,
        String nickname,
        String nationality
) {
}
