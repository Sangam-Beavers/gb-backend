package com.gb.community.domain.post.dto.response;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.common.util.UtcTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 게시글 단건 조회/작성/수정 공용 응답 (api-spec §2 작성 응답 · §3 단건/수정).
 *
 * <p>단건 조회의 {@code author_is_verified}(requirements §4 인증 배지)까지 포함하는 상위집합이라
 * 세 흐름이 한 DTO를 공유한다. 작성/수정 응답에도 author_is_verified가 함께 실린다(추가 정보, 무해).
 *
 * <p>JSON 필드명은 전역 SNAKE_CASE 설정에 위임({@code @JsonProperty} 미사용). boolean 필드명을
 * {@code authorIsVerified}로 둬 게터 {@code isAuthorIsVerified()} → 프로퍼티 {@code authorIsVerified}
 * → {@code author_is_verified}로 변환되게 한다(필드명을 'is'로 시작하면 'is'가 떨어져 어긋나는 함정 회피).
 *
 * <p>{@code is_author}는 "요청자 == 작성자" 여부 — 프론트가 수정·삭제 버튼 노출을 판단한다.
 * 명세 필드명이 is-접두라 boxed {@code Boolean}으로 둔다: primitive면 위 함정으로 {@code author}가 되고,
 * Boolean이면 게터 {@code getIsAuthor()} → 프로퍼티 {@code isAuthor} → {@code is_author}로 정확히 나간다.
 * 작성/수정 응답에서는 요청자가 곧 작성자이므로 항상 true다.
 *
 * <p>{@code is_liked}는 "요청자가 이 글을 좋아요했는지" 여부 — 프론트가 상세 화면 하트(♥/♡) 상태를
 * 그린다(프론트 개선 요청). is_author와 같은 요청자 기준 boolean이라 동일하게 boxed {@code Boolean}로
 * 둔다(null 없이 항상 true/false로 채움). 좋아요 저장의 중복(409) 판정과 동일한 EXISTS 조회로 계산하며,
 * 작성 응답에서는 방금 생성된 글이라 항상 false다(좋아요 행이 존재할 수 없음 — 조회 생략).
 */
@Getter
public class PostDetailResponse {

    @Schema(description = "게시글 UUID", example = "a1b2c3d4-0000-0000-0000-000000000001")
    private final String publicId;

    @Schema(description = "카테고리", example = "JOB",
            allowableValues = {"LIFE_INFO", "JOB", "VISA", "COUNTRY", "RESIDENCE", "QUESTION", "FREE"})
    private final String category;

    @Schema(description = "제목", example = "시급 9,000원 받고 일했는데 최저임금 미달인가요?")
    private final String title;

    @Schema(description = "본문(전체)", example = "베트남에서 온 외국인입니다. 같은 경험 있는 분 계시면 알려주세요.")
    private final String content;

    @Schema(description = "작성자 닉네임", example = "Minh")
    private final String authorNickname;

    @Schema(description = "작성자 인증 배지 여부", example = "true")
    private final boolean authorIsVerified;

    @Schema(description = "요청자가 작성자 본인인지 여부(수정·삭제 버튼 노출 판단용)", example = "false")
    private final Boolean isAuthor;

    @Schema(description = "요청자가 이 게시글을 좋아요했는지 여부(하트 상태 표시용). 작성 응답에선 항상 false",
            example = "true")
    private final Boolean isLiked;

    @Schema(description = "좋아요 수", example = "3")
    private final Integer likeCount;

    @Schema(description = "댓글 수", example = "2")
    private final Integer commentCount;

    @Schema(description = "작성 시각(ISO 8601, UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String createdAt;

    @Schema(description = "수정 시각(ISO 8601, UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String updatedAt;

    @Builder
    private PostDetailResponse(String publicId, String category, String title, String content,
                              String authorNickname, boolean authorIsVerified, Boolean isAuthor,
                              Boolean isLiked, Integer likeCount, Integer commentCount,
                              String createdAt, String updatedAt) {
        this.publicId = publicId;
        this.category = category;
        this.title = title;
        this.content = content;
        this.authorNickname = authorNickname;
        this.authorIsVerified = authorIsVerified;
        this.isAuthor = isAuthor;
        this.isLiked = isLiked;
        this.likeCount = likeCount;
        this.commentCount = commentCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * @param requesterUserPublicId 요청자(인증 JWT public_id). 작성자와 비교해 {@code is_author}를 계산한다 —
     *                              수정/삭제의 {@code verifyOwner}와 동일한 public_id equals 비교.
     *                              작성/수정 흐름은 요청자가 곧 (검증된) 작성자라 항상 true가 된다.
     * @param isLiked 요청자의 좋아요 여부. Service가 흐름별로 계산해 넘긴다 — 단건/수정은 likes EXISTS
     *                조회(좋아요 409 판정과 동일 조건), 작성은 false 고정(방금 생성된 글 — 조회 생략).
     */
    public static PostDetailResponse from(Post post, MemberInfo author, String requesterUserPublicId,
                                          boolean isLiked) {
        return PostDetailResponse.builder()
                .publicId(post.getPublicId())
                .category(post.getCategory().name())
                .title(post.getTitle())
                .content(post.getContent())
                .authorNickname(author.nickname())
                .authorIsVerified(author.isVerified())
                .isAuthor(post.getUserPublicId().equals(requesterUserPublicId))
                .isLiked(isLiked)
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .createdAt(UtcTime.toUtcZ(post.getCreatedAt()))
                .updatedAt(UtcTime.toUtcZ(post.getUpdatedAt()))
                .build();
    }
}