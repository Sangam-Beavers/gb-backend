package com.gb.document.domain.document.dto.response;

import com.gb.document.domain.document.entity.Document;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 분석 요청 응답(POST /api/v1/documents) + retry 응답.
 * uploadUrl / uploadHeaders / expiresAt은 retry에서는 null이고, 최초 제출에서는 Pre-signed PUT URL과
 * 그 URL로 업로드할 때 함께 보내야 하는 서명 헤더가 담긴다.
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

    @Schema(description = "uploadUrl로 PUT 업로드할 때 그대로 함께 보내야 하는 헤더(이름+값). "
            + "서명에 포함되어 있어 누락/변경 시 403이 나고 메타데이터가 오브젝트에 박히지 않는다. "
            + "retry 응답에서는 null.",
            example = "{\"Content-Type\":\"application/octet-stream\","
                    + "\"x-amz-meta-source\":\"production\","
                    + "\"x-amz-meta-document_id\":\"550e8400-e29b-41d4-a716-446655440000\"}")
    private final Map<String, String> uploadHeaders;

    @Schema(description = "uploadUrl 만료 시각(ISO 8601 UTC Z). retry 응답에서는 null.",
            example = "2026-05-29T10:00:00Z")
    private final String expiresAt;

    @Builder
    private SubmissionResponse(String publicId, String status, String uploadUrl,
                              Map<String, String> uploadHeaders, String expiresAt) {
        this.publicId = publicId;
        this.status = status;
        this.uploadUrl = uploadUrl;
        this.uploadHeaders = uploadHeaders;
        this.expiresAt = expiresAt;
    }

    /** 최초 제출 — uploadUrl + 업로드 시 보낼 서명 헤더 포함. */
    public static SubmissionResponse forSubmit(Document document, String uploadUrl,
                                               Map<String, String> uploadHeaders, Instant expiresAt) {
        return SubmissionResponse.builder()
                .publicId(document.getPublicId())
                .status(document.getStatus().name())
                .uploadUrl(uploadUrl)
                .uploadHeaders(uploadHeaders)
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
