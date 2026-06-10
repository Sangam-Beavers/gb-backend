package com.gb.appadmin.domain.member.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "앱 관리자용 회원 단건 뷰")
public record AppMemberResponse(
        String userPublicId,
        String email,
        String name,
        String nickname,
        String nationality,
        @Schema(description = "계정 상태(ACTIVE/SUSPENDED)")
        String status,
        @Schema(description = "KYC 상태(PENDING/APPROVED/REJECTED/NOT_SUBMITTED)")
        String kycStatus,
        @Schema(description = "커뮤니티 활동 제한 여부")
        boolean communityBanned,
        String joinedAt
) {}
