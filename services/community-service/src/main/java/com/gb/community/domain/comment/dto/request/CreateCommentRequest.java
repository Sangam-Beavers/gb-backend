package com.gb.community.domain.comment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * POST /api/v1/community/posts/{postId}/comments 요청 본문 — 댓글 작성.
 *
 * <p>대댓글은 본 사이클에서 미지원이라 {@code parent_comment_public_id} 필드는 받지 않는다 — 모든 댓글은
 * 최상위(parent_id=null)로 INSERT된다. 향후 대댓글 도입 시 필드 추가 + Service에 부모 검증·1-depth
 * 강제 로직을 추가한다(Comment.parent_id 컬럼·CommentResponse.parent_comment_public_id 필드는
 * 이미 준비됨).
 *
 * <p>JSON 필드는 전역 SNAKE_CASE 변환으로 camelCase로 정의한다({@code @JsonProperty} 미사용).
 * 빈 문자열·공백만 입력은 {@code @NotBlank}로 차단해 무의미한 댓글을 막는다.
 */
@Getter
@NoArgsConstructor
public class CreateCommentRequest {

    @Schema(description = "댓글 내용 (1~2000자)", example = "좋은 정보 감사합니다!", maxLength = 2000)
    @NotBlank(message = "댓글 내용은 필수입니다")
    @Size(max = 2000, message = "댓글은 2000자 이하여야 합니다")
    private String content;
}
