package com.gb.document.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "관리자 문서 페이지")
public record AdminDocumentPageResponse(
        List<AdminDocumentView> documents,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AdminDocumentPageResponse from(Page<AdminDocumentView> p) {
        return new AdminDocumentPageResponse(
                p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
