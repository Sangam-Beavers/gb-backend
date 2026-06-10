package com.gb.appadmin.domain.faq.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "FAQ 수정 요청 (null 필드는 변경 안 함)")
public record FaqUpdateRequest(
        @Schema(description = "질문") @Size(max = 300) String question,
        @Schema(description = "답변") String answer,
        @Schema(description = "카테고리") @Size(max = 50) String category,
        @Schema(description = "게시 여부") Boolean published,
        @Schema(description = "정렬 순서") Integer sortOrder
) {}
