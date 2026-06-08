package com.gb.admin.domain.transaction.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "관리자 거래 로그 페이지")
public record AdminTransactionPageResponse(
        @Schema(description = "거래 목록.")
        List<AdminTransactionResponse> transactions,

        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "42") long totalElements,
        @Schema(example = "3") int totalPages
) {

    public static AdminTransactionPageResponse from(Page<AdminTransactionResponse> page) {
        return new AdminTransactionPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
