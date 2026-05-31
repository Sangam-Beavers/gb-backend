package com.gb.community.domain.like.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 좋아요 저장/취소 응답 (api-spec §5).
 *
 * <p>명세 §5가 응답 바디 필드를 정의하지 않아, 최소 정보({@code post_public_id}, 갱신된 {@code like_count},
 * 현재 {@code liked} 여부)만 반환한다. 식별자는 {@code public_id}만 노출(내부 id 비노출),
 * 필드명 snake_case 변환은 전역 설정에 위임한다.
 *
 * <p>{@code likeCount}는 like_count 캐시를 원자적으로 증감(±1)한 뒤의 값이다. 좋아요 저장은 +1 후 값,
 * 취소는 -1 후 값(음수 방지). 동시 요청이 있으면 약간의 stale이 있을 수 있는 표시용 수치다.
 */
@Getter
public class PostLikeResponse {

    @Schema(description = "게시글 UUID", example = "a1b2c3d4-0000-0000-0000-000000000001")
    private final String postPublicId;

    @Schema(description = "갱신된 좋아요 수", example = "4")
    private final int likeCount;

    @Schema(description = "요청자의 현재 좋아요 여부(저장=true, 취소=false)", example = "true")
    private final boolean liked;

    private PostLikeResponse(String postPublicId, int likeCount, boolean liked) {
        this.postPublicId = postPublicId;
        this.likeCount = likeCount;
        this.liked = liked;
    }

    public static PostLikeResponse of(String postPublicId, int likeCount, boolean liked) {
        return new PostLikeResponse(postPublicId, likeCount, liked);
    }
}