package com.gb.appadmin.domain.notice.dto.response;

import com.gb.appadmin.domain.notice.entity.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "공지사항 응답")
public record NoticeResponse(
        @Schema(description = "공지 public_id", example = "aabbccdd-0001-0001-0001-000000000001")
        String publicId,
        @Schema(description = "제목", example = "서비스 점검 안내")
        String title,
        @Schema(description = "본문")
        String content,
        @Schema(description = "상단 고정 여부", example = "false")
        Boolean pinned,
        @Schema(description = "게시 여부", example = "true")
        Boolean published,
        @Schema(description = "생성 시각(UTC)", example = "2026-06-09T10:00:00")
        LocalDateTime createdAt,
        @Schema(description = "수정 시각(UTC)", example = "2026-06-09T10:00:00")
        LocalDateTime updatedAt
) {
    public static NoticeResponse from(Notice notice) {
        return new NoticeResponse(
                notice.getPublicId(),
                notice.getTitle(),
                notice.getContent(),
                notice.getPinned(),
                notice.getPublished(),
                notice.getCreatedAt(),
                notice.getUpdatedAt()
        );
    }
}
