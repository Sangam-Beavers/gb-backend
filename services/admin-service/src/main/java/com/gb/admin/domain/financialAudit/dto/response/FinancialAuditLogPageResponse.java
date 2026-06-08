package com.gb.admin.domain.financialAudit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "금융 감사 로그 페이지")
public record FinancialAuditLogPageResponse(
        List<FinancialAuditLogResponse> logs,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static FinancialAuditLogPageResponse from(Page<FinancialAuditLogResponse> p) {
        return new FinancialAuditLogPageResponse(
                p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
