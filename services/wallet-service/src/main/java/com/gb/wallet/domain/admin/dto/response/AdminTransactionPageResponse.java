package com.gb.wallet.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "관리자 거래 페이지")
public record AdminTransactionPageResponse(
        List<AdminTransactionView> transactions,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AdminTransactionPageResponse from(Page<AdminTransactionView> p) {
        return new AdminTransactionPageResponse(
                p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
