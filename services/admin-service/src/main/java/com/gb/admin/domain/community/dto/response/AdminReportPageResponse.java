package com.gb.admin.domain.community.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "신고 게시글 페이지")
public record AdminReportPageResponse(
        List<AdminReportResponse> reports,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "42") long totalElements,
        @Schema(example = "3") int totalPages
) {

    public static AdminReportPageResponse from(Page<AdminReportResponse> page) {
        return new AdminReportPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
