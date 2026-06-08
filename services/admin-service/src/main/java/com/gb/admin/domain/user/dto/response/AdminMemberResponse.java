package com.gb.admin.domain.user.dto.response;

import com.gb.admin.global.client.AdminMemberSummary;
import com.gb.admin.global.client.KycStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "관리자 회원 목록 단건 응답")
public record AdminMemberResponse(
        @Schema(example = "11111111-1111-1111-1111-111111111111") String userPublicId,
        @Schema(example = "linh@gb.com") String email,
        @Schema(example = "Nguyen Thi Linh") String name,
        @Schema(example = "Linh") String nickname,
        @Schema(example = "VN") String nationality,

        @Schema(description = "KYC 상태(SCREAMING_SNAKE_CASE).", example = "MATCHED",
                allowableValues = {"PENDING", "APPROVED", "REJECTED", "NEEDS_REVIEW", "MATCHED"})
        KycStatus kycStatus,

        @Schema(description = "신분증 종류(SCREAMING_SNAKE_CASE).", example = "ALIEN_REGISTRATION",
                allowableValues = {"ALIEN_REGISTRATION", "PASSPORT", "NATIONAL_ID"})
        String identityDocumentType,

        @Schema(description = "가입 시각(UTC).", example = "2026-05-20T09:12:00Z")
        LocalDateTime joinedAt
) {

    public static AdminMemberResponse from(AdminMemberSummary s) {
        return new AdminMemberResponse(
                s.userPublicId(),
                s.email(),
                s.name(),
                s.nickname(),
                s.nationality(),
                s.kycStatus(),
                s.identityDocumentType(),
                s.joinedAt()
        );
    }
}
