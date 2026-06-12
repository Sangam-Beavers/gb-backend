package com.gb.community.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "관리자 신고 콘텐츠 페이지")
public record AdminReportPageResponse(
        List<AdminReportView> reports,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    /** 수동 페이지네이션 결과로부터 생성. */
    public static AdminReportPageResponse of(List<AdminReportView> content,
                                              int page, int size, long totalElements) {
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return new AdminReportPageResponse(content, page, size, totalElements, totalPages);
    }
}
