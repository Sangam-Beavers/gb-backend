package com.gb.admin.global.client;

public record AdminMemberStats(
        long totalMembers,
        long pendingKycCount,
        long approvedKycCount,
        long rejectedKycCount,
        String kycPassRate,
        long newMembersToday
) {
}
