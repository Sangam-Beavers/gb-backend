package com.gb.admin.domain.auditLog.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "감사 로그 페이지 응답")
public record AuditLogPageResponse(
        @Schema(description = "감사 로그 목록.")
        List<AuditLogResponse> auditLogs,

        @Schema(description = "현재 페이지(0-based).", example = "0")
        int page,

        @Schema(description = "페이지 크기.", example = "20")
        int size,

        @Schema(description = "전체 요소 수.", example = "42")
        long totalElements,

        @Schema(description = "전체 페이지 수.", example = "3")
        int totalPages
) {

    public static AuditLogPageResponse from(Page<AuditLogResponse> page) {
        return new AuditLogPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
