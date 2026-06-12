package com.gb.community.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "신고당한 작성자 집계 단건")
public record AdminReportedAuthorView(

        @Schema(description = "작성자 publicId")
        String authorPublicId,

        @Schema(description = "총 신고 수")
        long totalReportCount,

        @Schema(description = "신고당한 콘텐츠(글+댓글) 수")
        long reportedContentCount
) {
}
