package com.gb.community.domain.post.dto.response;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.common.util.UtcTime;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
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
 */
@Getter
public class PostDetailResponse {

    @Schema(description = "게시글 UUID", example = "a1b2c3d4-0000-0000-0000-000000000001")
    private final String publicId;

    @Schema(description = "카테고리", example = "JOB",
            allowableValues = {"LIFE_INFO", "JOB", "VISA", "COUNTRY", "RESIDENCE", "QUESTION"})
    private final String category;

    @Schema(description = "제목", example = "시급 9,000원 받고 일했는데 최저임금 미달인가요?")
    private final String title;

    @Schema(description = "본문(전체)", example = "베트남에서 온 외국인입니다. 같은 경험 있는 분 계시면 알려주세요.")
    private final String content;

    // posts 스키마에 이미지 컬럼이 없고 post_images 테이블 미정 → 항상 빈 배열로 반환(작업 지시서 확정 사항 1).
    // TODO: post_images 테이블 확정 시 실제 URL 목록으로 교체.
    @Schema(description = "이미지 URL 목록(현재 미저장 — 항상 빈 배열)", example = "[]")
    private final List<String> imageUrls;

    @Schema(description = "작성자 닉네임", example = "Minh")
    private final String authorNickname;

    @Schema(description = "작성자 인증 배지 여부", example = "true")
    private final boolean authorIsVerified;

    // author_temperature: 명세 타입은 number지만 등급 문자열을 그대로 싣는다(작업 지시서 확정 사항 3). TODO 동일.
    @Schema(description = "작성자 이웃 온도 등급(명세 number와 불일치 — 등급 문자열 전달, TODO)",
            example = "GREEN", allowableValues = {"RED", "YELLOW", "GREEN", "PURPLE", "BLUE"})
    private final String authorTemperature;

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
                              List<String> imageUrls, String authorNickname, boolean authorIsVerified,
                              String authorTemperature, Integer likeCount, Integer commentCount,
                              String createdAt, String updatedAt) {
        this.publicId = publicId;
        this.category = category;
        this.title = title;
        this.content = content;
        this.imageUrls = imageUrls;
        this.authorNickname = authorNickname;
        this.authorIsVerified = authorIsVerified;
        this.authorTemperature = authorTemperature;
        this.likeCount = likeCount;
        this.commentCount = commentCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static PostDetailResponse from(Post post, MemberInfo author) {
        return PostDetailResponse.builder()
                .publicId(post.getPublicId())
                .category(post.getCategory().name())
                .title(post.getTitle())
                .content(post.getContent())
                .imageUrls(List.of()) // 미저장 — 항상 빈 배열(확정 사항 1)
                .authorNickname(author.nickname())
                .authorIsVerified(author.isVerified())
                .authorTemperature(author.temperatureGrade())
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .createdAt(UtcTime.toUtcZ(post.getCreatedAt()))
                .updatedAt(UtcTime.toUtcZ(post.getUpdatedAt()))
                .build();
    }
}