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
 * 신분증 인증 요청 접수 응답. (POST /api/v1/members/me/verification, 201)
 *
 * <p>데모 흐름에서는 형식 검증 통과 시 즉시 승인하므로 {@code status}는 {@code APPROVED}로 내려간다
 * (관리자 검토 단계를 붙이면 {@code PENDING}). {@code submittedAt}은 요청 접수(생성) 시각이다.
 */
@Getter
public class VerificationSubmitResponse {

    @Schema(description = "인증 처리 상태(데모: 즉시 APPROVED)",
            allowableValues = {"PENDING", "APPROVED", "REJECTED"}, example = "APPROVED")
    private final String status;

    @Schema(description = "제출 시각(ISO 8601 UTC Z)", example = "2026-05-25T10:00:00Z")
    private final String submittedAt;

    @Builder
    private VerificationSubmitResponse(String status, String submittedAt) {
        this.status = status;
        this.submittedAt = submittedAt;
    }

    public static VerificationSubmitResponse from(UserVerification verification) {
        return VerificationSubmitResponse.builder()
                .status(verification.getStatus().name())
                .submittedAt(toUtcZ(verification.getCreatedAt()))
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
