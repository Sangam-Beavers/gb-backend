package com.gb.community.domain.qna.dto.response;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.global.common.util.UtcTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 주요 QnA 목록 항목 (api-spec §8).
 *
 * <p>4개 필드만 노출 — 작성자 정보·본문 미리보기는 포함하지 않는다(빠른 응답·MemberClient 호출 회피).
 * 본문/작성자/카테고리 등 상세는 단건 조회(§3 GET /posts/{id}) API로 별도 조회한다.
 *
 * <p>JSON 필드명은 전역 SNAKE_CASE 설정으로 camelCase → snake_case 변환된다 — {@code @JsonProperty} 미사용.
 * 식별자는 {@code public_id}만 노출(내부 id 비노출, conventions §5), 시각은 UTC Z 문자열.
 */
@Getter
public class QnaPostResponse {

    @Schema(description = "게시글 UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private final String publicId;

    @Schema(description = "제목", example = "E-9 비자로 근무지 변경이 가능한가요?")
    private final String title;

    @Schema(description = "답변(댓글) 수", example = "7")
    private final int commentCount;

    @Schema(description = "작성 시각(ISO 8601, UTC Z)", example = "2026-05-20T09:00:00Z")
    private final String createdAt;

    @Builder
    private QnaPostResponse(String publicId, String title, int commentCount, String createdAt) {
        this.publicId = publicId;
        this.title = title;
        this.commentCount = commentCount;
        this.createdAt = createdAt;
    }

    /** Post → 응답 항목 변환 (CLAUDE.md §4 — Entity → DTO는 정적 from). */
    public static QnaPostResponse from(Post post) {
        return QnaPostResponse.builder()
                .publicId(post.getPublicId())
                .title(post.getTitle())
                .commentCount(post.getCommentCount())
                .createdAt(UtcTime.toUtcZ(post.getCreatedAt()))
                .build();
    }
}
