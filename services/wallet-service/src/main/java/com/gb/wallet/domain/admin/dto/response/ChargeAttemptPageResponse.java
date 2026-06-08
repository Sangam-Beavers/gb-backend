package com.gb.wallet.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "충전 실패 큐 페이지")
public record ChargeAttemptPageResponse(
        List<ChargeAttemptView> attempts,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static ChargeAttemptPageResponse from(Page<ChargeAttemptView> p) {
        return new ChargeAttemptPageResponse(
                p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
