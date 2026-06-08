package com.gb.admin.domain.financialAudit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "충전 실패 큐 페이지")
public record AdminChargeAttemptPageResponse(
        List<AdminChargeAttemptResponse> attempts,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AdminChargeAttemptPageResponse from(Page<AdminChargeAttemptResponse> p) {
        return new AdminChargeAttemptPageResponse(
                p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
