package com.gb.community.domain.post.dto.response;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.common.util.UtcTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 게시글 목록 항목 (api-spec §1 {@code posts[]}).
 *
 * <p>JSON 필드명은 전역 SNAKE_CASE 설정으로 camelCase → snake_case 변환된다 — {@code @JsonProperty} 미사용.
 * 식별자는 {@code public_id}만 노출(내부 id 비노출), 시각은 UTC Z 문자열.
 *
 * <p>작성자 표시 정보(닉네임)는 {@link MemberInfo}(MemberClient 조회 결과)에서 가져온다 —
 * MSA 경계 회원 참조라 DB 직접 SELECT 없이 client로 받는다(CLAUDE.md §7).
 */
@Getter
public class PostSummaryResponse {

    /** 본문 미리보기 최대 길이. 명세에 수치가 없어 표시용으로 합리적인 값을 둔다. */
    private static final int PREVIEW_MAX_LENGTH = 100;

    @Schema(description = "게시글 UUID", example = "a1b2c3d4-0000-0000-0000-000000000001")
    private final String publicId;

    @Schema(description = "카테고리", example = "JOB",
            allowableValues = {"LIFE_INFO", "JOB", "VISA", "COUNTRY", "RESIDENCE", "QUESTION"})
    private final String category;

    @Schema(description = "제목", example = "시급 9,000원 받고 일했는데 최저임금 미달인가요?")
    private final String title;

    @Schema(description = "본문 미리보기(앞 100자, 초과 시 … 표기)", example = "베트남에서 온 외국인입니다. 같은 경험 있는 분 계시면…")
    private final String contentPreview;

    @Schema(description = "작성자 닉네임", example = "Minh")
    private final String authorNickname;

    @Schema(description = "좋아요 수", example = "3")
    private final Integer likeCount;

    @Schema(description = "댓글 수", example = "2")
    private final Integer commentCount;

    @Schema(description = "작성 시각(ISO 8601, UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String createdAt;

    @Builder
    private PostSummaryResponse(String publicId, String category, String title, String contentPreview,
                               String authorNickname,
                               Integer likeCount, Integer commentCount, String createdAt) {
        this.publicId = publicId;
        this.category = category;
        this.title = title;
        this.contentPreview = contentPreview;
        this.authorNickname = authorNickname;
        this.likeCount = likeCount;
        this.commentCount = commentCount;
        this.createdAt = createdAt;
    }

    public static PostSummaryResponse from(Post post, MemberInfo author) {
        return PostSummaryResponse.builder()
                .publicId(post.getPublicId())
                .category(post.getCategory().name())
                .title(post.getTitle())
                .contentPreview(preview(post.getContent()))
                .authorNickname(author.nickname())
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .createdAt(UtcTime.toUtcZ(post.getCreatedAt()))
                .build();
    }

    /** 본문 앞부분을 잘라 미리보기를 만든다. 길면 말줄임표(…)를 붙인다. */
    private static String preview(String content) {
        if (content == null) {
            return null;
        }
        if (content.length() <= PREVIEW_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, PREVIEW_MAX_LENGTH) + "…";
    }
}
