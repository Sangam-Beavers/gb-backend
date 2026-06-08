package com.gb.community.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "관리자 신고 게시글 페이지")
public record AdminReportPageResponse(
        List<AdminReportView> reports,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AdminReportPageResponse from(Page<AdminReportView> p) {
        return new AdminReportPageResponse(
                p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
