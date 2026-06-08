package com.gb.member.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원 통계 응답")
public record MemberStatsResponse(
        long totalMembers,
        long pendingKycCount,
        long approvedKycCount,
        long rejectedKycCount,
        @Schema(description = "KYC 통과율 0.0~1.0", example = "0.8730")
        String kycPassRate,
        long newMembersToday
) {
}
