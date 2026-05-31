package com.gb.community.domain.post.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * 게시글 목록·검색 응답 (api-spec §1).
 *
 * <p>{@code posts} 배열 + 페이지 메타(page/size/total_elements/total_pages). 필드명은 전역
 * SNAKE_CASE 설정에 위임한다({@code totalElements} → {@code total_elements}).
 */
@Getter
public class PostListResponse {

    @Schema(description = "게시글 목록")
    private final List<PostSummaryResponse> posts;

    @Schema(description = "현재 페이지(0부터)", example = "0")
    private final int page;

    @Schema(description = "페이지당 게시글 수", example = "20")
    private final int size;

    @Schema(description = "전체 게시글 수", example = "42")
    private final long totalElements;

    @Schema(description = "전체 페이지 수", example = "3")
    private final int totalPages;

    private PostListResponse(List<PostSummaryResponse> posts, int page, int size,
                            long totalElements, int totalPages) {
        this.posts = posts;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

    public static PostListResponse of(List<PostSummaryResponse> posts, int page, int size,
                                      long totalElements, int totalPages) {
        return new PostListResponse(posts, page, size, totalElements, totalPages);
    }
}
