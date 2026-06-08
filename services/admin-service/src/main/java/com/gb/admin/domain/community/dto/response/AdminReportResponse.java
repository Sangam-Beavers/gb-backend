package com.gb.admin.domain.community.dto.response;

import com.gb.admin.global.client.AdminReportSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "신고 게시글 단건")
public record AdminReportResponse(
        @Schema(example = "cccccccc-0001-0000-0000-000000000001") String postPublicId,
        @Schema(example = "부적절한 구인 광고") String title,
        @Schema(example = "77777777-7777-7777-7777-777777777777") String authorPublicId,
        @Schema(example = "unknown77") String authorNickname,
        @Schema(example = "8") long reportCount,
        @Schema(description = "신고 카테고리(SCREAMING_SNAKE_CASE).", example = "SPAM",
                allowableValues = {"SPAM", "ABUSE", "FRAUD", "OTHER"})
        String category,
        @Schema(example = "2026-06-07T08:00:00Z") LocalDateTime lastReportedAt
) {

    public static AdminReportResponse from(AdminReportSummary s) {
        return new AdminReportResponse(
                s.postPublicId(), s.title(), s.authorPublicId(), s.authorNickname(),
                s.reportCount(), s.category(), s.lastReportedAt()
        );
    }
}
