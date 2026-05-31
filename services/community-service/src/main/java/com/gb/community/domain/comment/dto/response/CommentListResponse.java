package com.gb.community.domain.comment.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * 댓글 목록 조회 응답 (api-spec §7).
 *
 * <p>{@code comments} 배열 + 페이지 메타(page/size/total_elements/total_pages). 게시글 목록
 * ({@code PostListResponse})과 동일한 메타 구조를 그대로 따른다(항목 타입만 {@link CommentResponse}).
 * 필드명 snake_case 변환은 전역 설정에 위임한다({@code totalElements} → {@code total_elements}).
 */
@Getter
public class CommentListResponse {

    @Schema(description = "댓글 목록(작성순)")
    private final List<CommentResponse> comments;

    @Schema(description = "현재 페이지(0부터)", example = "0")
    private final int page;

    @Schema(description = "페이지당 댓글 수", example = "20")
    private final int size;

    @Schema(description = "전체 댓글 수", example = "2")
    private final long totalElements;

    @Schema(description = "전체 페이지 수", example = "1")
    private final int totalPages;

    private CommentListResponse(List<CommentResponse> comments, int page, int size,
                               long totalElements, int totalPages) {
        this.comments = comments;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

    public static CommentListResponse of(List<CommentResponse> comments, int page, int size,
                                         long totalElements, int totalPages) {
        return new CommentListResponse(comments, page, size, totalElements, totalPages);
    }
}
