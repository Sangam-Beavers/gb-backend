package com.gb.document.domain.document.dto.response;

import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 진행 상태 조회 응답(GET /api/v1/documents/{id}/status). 프론트 폴링용 — 페이로드 가벼움.
 */
@Getter
public class DocumentStatusResponse {

    /** ANALYZING 상태에서 프론트가 사용자에게 보여줄 대략적인 남은 시간 표시. 명세 §2. */
    private static final int ANALYZING_ESTIMATED_MINUTES = 3;

    @Schema(description = "분석 요청 public_id", example = "550e8400-e29b-41d4-a716-446655440000")
    private final String publicId;

    @Schema(description = "현재 분석 상태", example = "ANALYZING",
            allowableValues = {"ANALYZING", "COMPLETED", "FAILED"})
    private final String status;

    @Schema(description = "분석 중일 때 표시할 예상 남은 분(고정값). 완료/실패면 0.",
            example = "3")
    private final int estimatedMinutes;

    @Builder
    private DocumentStatusResponse(String publicId, String status, int estimatedMinutes) {
        this.publicId = publicId;
        this.status = status;
        this.estimatedMinutes = estimatedMinutes;
    }

    public static DocumentStatusResponse from(Document document) {
        int estimated = document.getStatus() == DocumentStatus.ANALYZING ? ANALYZING_ESTIMATED_MINUTES : 0;
        return DocumentStatusResponse.builder()
                .publicId(document.getPublicId())
                .status(document.getStatus().name())
                .estimatedMinutes(estimated)
                .build();
    }
}
