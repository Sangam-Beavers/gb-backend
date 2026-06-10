package com.gb.appadmin.domain.faq.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "FAQ 등록 요청")
public record FaqCreateRequest(
        @Schema(description = "질문", example = "환전 수수료는 얼마인가요?")
        @NotBlank @Size(max = 300)
        String question,

        @Schema(description = "답변")
        @NotBlank
        String answer,

        @Schema(description = "카테고리(GENERAL/TRANSFER/EXCHANGE/DOCUMENT/ACCOUNT)", example = "EXCHANGE")
        @NotBlank @Size(max = 50)
        String category,

        @Schema(description = "즉시 게시 여부", example = "true")
        Boolean published,

        @Schema(description = "카테고리 내 정렬 순서(낮을수록 위)", example = "0")
        Integer sortOrder
) {}
