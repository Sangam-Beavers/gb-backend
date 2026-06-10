package com.gb.appadmin.domain.faq.dto.response;

import com.gb.appadmin.domain.faq.entity.Faq;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "FAQ 응답")
public record FaqResponse(
        @Schema(description = "FAQ public_id") String publicId,
        @Schema(description = "질문") String question,
        @Schema(description = "답변") String answer,
        @Schema(description = "카테고리") String category,
        @Schema(description = "게시 여부") Boolean published,
        @Schema(description = "정렬 순서") Integer sortOrder,
        @Schema(description = "생성 시각(UTC)") LocalDateTime createdAt,
        @Schema(description = "수정 시각(UTC)") LocalDateTime updatedAt
) {
    public static FaqResponse from(Faq faq) {
        return new FaqResponse(
                faq.getPublicId(),
                faq.getQuestion(),
                faq.getAnswer(),
                faq.getCategory(),
                faq.getPublished(),
                faq.getSortOrder(),
                faq.getCreatedAt(),
                faq.getUpdatedAt()
        );
    }
}
