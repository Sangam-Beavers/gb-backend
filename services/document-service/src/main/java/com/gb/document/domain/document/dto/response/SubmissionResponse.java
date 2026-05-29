package com.gb.document.domain.document.dto.response;

import com.gb.document.domain.document.entity.Document;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 분석 요청 응답(POST /api/v1/documents) + retry 응답.
 * uploadUrl / expiresAt은 retry에서는 null이고, 최초 제출에서는 Pre-signed PUT URL이 담긴다.
 */
@Getter
public class SubmissionResponse {

    @Schema(description = "분석 요청 식별자(public_id). 이후 상태/결과 조회 시 사용.",
            example = "550e8400-e29b-41d4-a716-446655440000")
    private final String publicId;

    @Schema(description = "현재 분석 상태", example = "ANALYZING",
            allowableValues = {"ANALYZING", "COMPLETED", "FAILED"})
    private final String status;

    @Schema(description = "Pre-signed PUT URL. retry 응답에서는 null.",
            example = "https://s3.ap-northeast-2.amazonaws.com/...")
    private final String uploadUrl;

    @Schema(description = "uploadUrl 만료 시각(ISO 8601 UTC Z). retry 응답에서는 null.",
            example = "2026-05-29T10:00:00Z")
    private final String expiresAt;

    @Builder
    private SubmissionResponse(String publicId, String status, String uploadUrl, String expiresAt) {
        this.publicId = publicId;
        this.status = status;
        this.uploadUrl = uploadUrl;
        this.expiresAt = expiresAt;
    }

    /** 최초 제출 — uploadUrl 포함. */
    public static SubmissionResponse forSubmit(Document document, String uploadUrl, Instant expiresAt) {
        return SubmissionResponse.builder()
                .publicId(document.getPublicId())
                .status(document.getStatus().name())
                .uploadUrl(uploadUrl)
                .expiresAt(toUtcZ(expiresAt))
                .build();
    }

    /** retry — uploadUrl/expiresAt 없이 상태만 회신. */
    public static SubmissionResponse forRetry(Document document) {
        return SubmissionResponse.builder()
                .publicId(document.getPublicId())
                .status(document.getStatus().name())
                .build();
    }

    private static String toUtcZ(Instant instant) {
        if (instant == null) return null;
        return DateTimeFormatter.ISO_INSTANT.format(instant.atOffset(ZoneOffset.UTC)
                .toInstant().truncatedTo(ChronoUnit.SECONDS));
    }
}
