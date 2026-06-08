package com.gb.admin.global.client;

import java.time.LocalDateTime;

/**
 * 관리자 회원 목록의 단건 요약. MSA 경계를 넘는 값이라 admin-service 자체 모델.
 */
public record AdminMemberSummary(
        String userPublicId,
        String email,
        String name,
        String nickname,
        String nationality,
        KycStatus kycStatus,
        String identityDocumentType,
        LocalDateTime joinedAt
) {
}
