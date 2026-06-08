package com.gb.admin.domain.document.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "AI 문서 분석 페이지")
public record AdminDocumentPageResponse(
        List<AdminDocumentResponse> documents,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "42") long totalElements,
        @Schema(example = "3") int totalPages
) {

    public static AdminDocumentPageResponse from(Page<AdminDocumentResponse> page) {
        return new AdminDocumentPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
