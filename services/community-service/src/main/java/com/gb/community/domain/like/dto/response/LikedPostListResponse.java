package com.gb.community.domain.like.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * 관심글 목록 응답 (api-spec §4).
 *
 * <p>{@code posts} 배열 + 페이지 메타(page/size/total_elements/total_pages). 게시글 목록
 * ({@code PostListResponse})과 동일한 메타 구조이며 항목 타입만 {@link LikedPostSummaryResponse}
 * (liked_at 포함)다. 필드명 snake_case 변환은 전역 설정에 위임한다({@code totalElements} → {@code total_elements}).
 */
@Getter
public class LikedPostListResponse {

    @Schema(description = "관심글 목록")
    private final List<LikedPostSummaryResponse> posts;

    @Schema(description = "현재 페이지(0부터)", example = "0")
    private final int page;

    @Schema(description = "페이지당 게시글 수", example = "20")
    private final int size;

    @Schema(description = "전체 관심글 수", example = "5")
    private final long totalElements;

    @Schema(description = "전체 페이지 수", example = "1")
    private final int totalPages;

    private LikedPostListResponse(List<LikedPostSummaryResponse> posts, int page, int size,
                                  long totalElements, int totalPages) {
        this.posts = posts;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

    public static LikedPostListResponse of(List<LikedPostSummaryResponse> posts, int page, int size,
                                           long totalElements, int totalPages) {
        return new LikedPostListResponse(posts, page, size, totalElements, totalPages);
    }
}