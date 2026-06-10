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
 *
 * <p>{@code author_public_id}는 작성자 식별자(UUID) — 프론트가 사진 미설정 시 기본 아바타(identicon)를
 * 작성자별로 결정적 생성하는 시드로 쓴다(마이페이지 프로필과 동일 시드 → 같은 사용자는 어디서든 같은 그림).
 *
 * <p>{@code is_author}는 "요청자 == 작성자" 여부 — 프론트가 수정·삭제 버튼 노출을 판단한다.
 * (author_public_id로 프론트가 직접 비교할 수도 있으나, 서버가 계산한 값을 함께 내려 편의를 둔다.) 필드 타입을 boxed
 * {@code Boolean}으로 둔 이유: primitive {@code boolean isAuthor}면 Lombok 게터가 {@code isAuthor()}
 * → Jackson 프로퍼티 {@code author}로 'is'가 떨어져 {@code author}로 직렬화되는 함정이 있다.
 * Boolean이면 게터가 {@code getIsAuthor()} → 프로퍼티 {@code isAuthor} → snake_case {@code is_author}.
 * (인증 필수 API라 값은 항상 채워진다 — null 의미 없음.)
 */
@Getter
public class PostSummaryResponse {

    /** 본문 미리보기 최대 길이. 명세에 수치가 없어 표시용으로 합리적인 값을 둔다. */
    private static final int PREVIEW_MAX_LENGTH = 100;

    @Schema(description = "게시글 UUID", example = "a1b2c3d4-0000-0000-0000-000000000001")
    private final String publicId;

    @Schema(description = "카테고리", example = "JOB",
            allowableValues = {"LIFE_INFO", "JOB", "VISA", "COUNTRY", "RESIDENCE", "QUESTION", "FREE"})
    private final String category;

    @Schema(description = "제목", example = "시급 9,000원 받고 일했는데 최저임금 미달인가요?")
    private final String title;

    @Schema(description = "본문 미리보기(앞 100자, 초과 시 … 표기)", example = "베트남에서 온 외국인입니다. 같은 경험 있는 분 계시면…")
    private final String contentPreview;

    @Schema(description = "작성 언어 코드(ko/en/vi/fil)", example = "ko")
    private final String language;

    @Schema(description = "작성자 식별자(UUID). 사진 미설정 시 기본 아바타 시드로 사용",
            example = "11111111-1111-1111-1111-111111111111")
    private final String authorPublicId;

    @Schema(description = "작성자 닉네임", example = "Minh")
    private final String authorNickname;

    @Schema(description = "작성자 프로필 사진 URL. 미설정 시 null(프론트는 기본 아바타로 대체)",
            example = "null", nullable = true)
    private final String authorProfileImageUrl;

    @Schema(description = "작성자 신뢰등급(마일스톤 기반 — 이슈 #194, Phase 2 확장). 표시정보 조회 실패·누락 시 NEWCOMER 폴백",
            allowableValues = {"NEWCOMER", "VERIFIED", "CONNECTED", "TRUSTED"}, example = "VERIFIED")
    private final String authorTrustGrade;

    @Schema(description = "요청자가 작성자 본인인지 여부(수정·삭제 버튼 노출 판단용)", example = "false")
    private final Boolean isAuthor;

    @Schema(description = "좋아요 수", example = "3")
    private final Integer likeCount;

    @Schema(description = "댓글 수", example = "2")
    private final Integer commentCount;

    @Schema(description = "작성 시각(ISO 8601, UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String createdAt;

    @Builder
    private PostSummaryResponse(String publicId, String category, String title, String contentPreview,
                               String language, String authorPublicId, String authorNickname,
                               String authorProfileImageUrl, String authorTrustGrade, Boolean isAuthor,
                               Integer likeCount, Integer commentCount, String createdAt) {
        this.publicId = publicId;
        this.category = category;
        this.title = title;
        this.contentPreview = contentPreview;
        this.language = language;
        this.authorPublicId = authorPublicId;
        this.authorNickname = authorNickname;
        this.authorProfileImageUrl = authorProfileImageUrl;
        this.authorTrustGrade = authorTrustGrade;
        this.isAuthor = isAuthor;
        this.likeCount = likeCount;
        this.commentCount = commentCount;
        this.createdAt = createdAt;
    }

    /**
     * @param requesterUserPublicId 요청자(인증 JWT public_id). 작성자와 비교해 {@code is_author}를 계산한다 —
     *                              수정/삭제의 {@code verifyOwner}와 동일한 public_id equals 비교.
     */
    public static PostSummaryResponse from(Post post, MemberInfo author, String requesterUserPublicId) {
        return PostSummaryResponse.builder()
                .publicId(post.getPublicId())
                .category(post.getCategory().name())
                .title(post.getTitle())
                .contentPreview(preview(post.getContent()))
                .language(post.getLanguage())
                .authorPublicId(post.getUserPublicId())
                .authorNickname(author.nickname())
                .authorProfileImageUrl(author.profileImageUrl())
                .authorTrustGrade(author.trustGrade())
                .isAuthor(post.getUserPublicId().equals(requesterUserPublicId))
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
