package com.gb.wallet.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "관리자 감사 로그 페이지")
public record TransactionAuditLogPageResponse(
        List<TransactionAuditLogView> logs,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static TransactionAuditLogPageResponse from(Page<TransactionAuditLogView> p) {
        return new TransactionAuditLogPageResponse(
                p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
