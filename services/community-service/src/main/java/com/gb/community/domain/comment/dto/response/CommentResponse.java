package com.gb.community.domain.comment.dto.response;

import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.common.util.UtcTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 댓글 목록 항목 (api-spec §7 목록 = §6 댓글 응답 항목 형태).
 *
 * <p>JSON 필드명은 전역 SNAKE_CASE 설정으로 camelCase → snake_case 변환된다 — {@code @JsonProperty} 미사용.
 * 식별자는 {@code public_id}만 노출(내부 id 비노출), 시각은 UTC Z 문자열(CLAUDE.md §5).
 *
 * <p>작성자 표시 정보(닉네임/인증배지)는 {@link MemberInfo}(MemberClient 조회 결과)에서 가져온다 —
 * MSA 경계 회원 참조라 DB 직접 SELECT 없이 client로 받는다(CLAUDE.md §7). 댓글 응답에는 이웃 온도가 없어
 * (api-spec §6) {@code temperatureGrade}는 싣지 않는다.
 *
 * <p>boolean 필드명을 {@code authorIsVerified}로 둬 게터 {@code isAuthorIsVerified()} → 프로퍼티
 * {@code authorIsVerified} → {@code author_is_verified}로 변환되게 한다(필드명을 'is'로 시작하면 'is'가
 * 떨어져 어긋나는 함정 회피 — PostDetailResponse와 동일).
 */
@Getter
public class CommentResponse {

    @Schema(description = "댓글 UUID", example = "c1d2e3f4-0000-0000-0000-000000000001")
    private final String publicId;

    @Schema(description = "게시글 UUID", example = "a1b2c3d4-0000-0000-0000-000000000001")
    private final String postPublicId;

    // parent_comment_public_id: 최상위 댓글이면 null, 대댓글이면 부모 댓글 UUID(api-spec §6).
    // 본 PR은 댓글 목록 조회만 — 대댓글은 별도 이슈라 현재 데이터에 대댓글이 없어(엔티티 parentId는 항상 null)
    // 항상 null로 나간다. 명세 형태와 1:1을 유지하기 위해 필드는 노출한다.
    // TODO: 대댓글 도입 시 Comment.parentId(내부 id) → 부모 댓글 public_id 해석을 서비스에 추가.
    @Schema(description = "부모 댓글 UUID(최상위면 null). 대댓글 미구현 — 현재 항상 null",
            example = "null", nullable = true)
    private final String parentCommentPublicId;

    @Schema(description = "댓글 내용", example = "저도 작년에 똑같은 일 겪었어요. 노동부 1350에 신고해 차액 다 받았어요.")
    private final String content;

    @Schema(description = "작성자 닉네임", example = "Minh")
    private final String authorNickname;

    @Schema(description = "작성자 인증 배지 여부", example = "true")
    private final boolean authorIsVerified;

    @Schema(description = "작성 시각(ISO 8601, UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String createdAt;

    @Builder
    private CommentResponse(String publicId, String postPublicId, String parentCommentPublicId,
                            String content, String authorNickname, boolean authorIsVerified,
                            String createdAt) {
        this.publicId = publicId;
        this.postPublicId = postPublicId;
        this.parentCommentPublicId = parentCommentPublicId;
        this.content = content;
        this.authorNickname = authorNickname;
        this.authorIsVerified = authorIsVerified;
        this.createdAt = createdAt;
    }

    /**
     * Comment + 작성자 표시 정보 → 응답 항목 변환 (CLAUDE.md §4 — Entity → DTO는 정적 from).
     *
     * <p>{@code postPublicId}는 서비스가 이미 확보한 게시글 public_id를 넘긴다 — 댓글마다
     * {@code comment.getPost().getPublicId()}로 LAZY 로딩하지 않기 위함(같은 게시글이라 값도 동일).
     */
    public static CommentResponse from(Comment comment, MemberInfo author, String postPublicId) {
        return CommentResponse.builder()
                .publicId(comment.getPublicId())
                .postPublicId(postPublicId)
                .parentCommentPublicId(null) // 대댓글 미구현(범위 밖) — 위 필드 주석 참고
                .content(comment.getContent())
                .authorNickname(author.nickname())
                .authorIsVerified(author.isVerified())
                .createdAt(UtcTime.toUtcZ(comment.getCreatedAt()))
                .build();
    }
}
