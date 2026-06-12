package com.gb.community.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "특정 작성자가 받은 신고 상세")
public record AdminReportedAuthorDetailResponse(

        @Schema(description = "작성자 publicId")
        String authorPublicId,

        @Schema(description = "신고당한 콘텐츠 목록 (글+댓글)")
        List<AdminReportView> reportedContents
) {
}
