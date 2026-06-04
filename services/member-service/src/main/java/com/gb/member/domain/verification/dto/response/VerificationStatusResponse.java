package com.gb.member.domain.verification.dto.response;

import com.gb.member.domain.verification.entity.UserVerification;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 신분증 인증 상태 조회 응답. (GET /api/v1/members/me/verification)
 *
 * <p>시각은 wallet/community/profile DTO와 동일하게 ISO-8601 UTC {@code Z} 문자열로 직렬화한다.
 */
@Getter
public class VerificationStatusResponse {

    @Schema(description = "제출 신분증 유형",
            allowableValues = {"ALIEN_REGISTRATION", "PASSPORT", "NATIONAL_ID"})
    private final String identityDocumentType;

    @Schema(description = "인증 상태", allowableValues = {"PENDING", "APPROVED", "REJECTED"})
    private final String status;

    @Schema(description = "검토 시각(ISO 8601 UTC Z). 미검토 시 null", nullable = true,
            example = "2026-05-22T14:00:00Z")
    private final String reviewedAt;

    @Schema(description = "인증 요청 시각(ISO 8601 UTC Z)", example = "2026-05-20T09:00:00Z")
    private final String createdAt;

    @Builder
    private VerificationStatusResponse(String identityDocumentType, String status,
                                       String reviewedAt, String createdAt) {
        this.identityDocumentType = identityDocumentType;
        this.status = status;
        this.reviewedAt = reviewedAt;
        this.createdAt = createdAt;
    }

    public static VerificationStatusResponse from(UserVerification verification) {
        return VerificationStatusResponse.builder()
                .identityDocumentType(verification.getDocumentType().name())
                .status(verification.getStatus().name())
                .reviewedAt(toUtcZ(verification.getReviewedAt()))
                .createdAt(toUtcZ(verification.getCreatedAt()))
                .build();
    }

    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
