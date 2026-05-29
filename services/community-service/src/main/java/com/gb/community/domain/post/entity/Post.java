package com.gb.community.domain.post.entity;

import com.gb.community.global.common.entity.BaseSoftDeleteEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 커뮤니티 게시글 엔티티 — <b>최소 스켈레톤</b>.
 *
 * <p>docs/database.md §4 posts 테이블 SSOT 그대로. CRUD API는 본 PR 범위 밖
 * (커뮤팀이 본격 작업 시작 시점에 controller/service/dto 추가).
 *
 * <p>여기는 챗봇 MCP2(`search_community_posts`)가 mcp_reader 계정으로
 * 직접 SELECT할 데이터의 생성 기반. ddl-auto:update가 이 엔티티를 보고
 * {@code community_db.posts} 테이블을 자동 생성한다.
 *
 * <p>회원 참조: {@code user_public_id}는 member 도메인 users.public_id 논리 참조
 * (CLAUDE.md §7 — MSA 경계 넘는 회원 참조는 UUID, 물리 FK 금지).
 */
@Entity
@Getter
@Table(name = "posts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends BaseSoftDeleteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자 (UUID). conventions §0 — 내부 id는 응답/URL에 노출 금지. */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /** 작성자 (member-service users.public_id 논리 참조). */
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30, nullable = false)
    private PostCategory category;

    /** 작성 언어 코드 (ko, en, vi 등). */
    @Column(name = "language", length = 10, nullable = false)
    private String language;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 번역 캐시 — 동일 언어 재요청 시 AWS Translate 재호출 없이 반환 (docs §4). */
    @Column(name = "translated_title", length = 255)
    private String translatedTitle;

    @Lob
    @Column(name = "translated_content", columnDefinition = "TEXT")
    private String translatedContent;

    @Column(name = "translated_language", length = 10)
    private String translatedLanguage;

    /** 조회수 (Redis 카운터 → 배치 동기화 가능, docs §4). */
    @Column(name = "view_count", nullable = false)
    private Integer viewCount;

    /** likes 테이블 집계값 캐시. */
    @Column(name = "like_count", nullable = false)
    private Integer likeCount;

    /** comments 테이블 집계값 캐시. */
    @Column(name = "comment_count", nullable = false)
    private Integer commentCount;

    @Builder
    private Post(String publicId, String userPublicId, PostCategory category,
                 String language, String title, String content) {
        this.publicId = publicId;
        this.userPublicId = userPublicId;
        this.category = category;
        this.language = language;
        this.title = title;
        this.content = content;
        // 카운터는 기본 0
        this.viewCount = 0;
        this.likeCount = 0;
        this.commentCount = 0;
    }

    /**
     * 댓글 추가 시 카운터 캐시 정합 유지용 도메인 메서드.
     *
     * <p>{@code @Setter} 금지(CLAUDE.md §4 Entity 규칙)라 외부에서 직접 증가시킬 수 없어 도메인 메서드로 노출.
     * 시드 단계와 향후 CRUD 작성 시 동일하게 사용한다.
     */
    public void increaseCommentCount() {
        this.commentCount += 1;
    }
}
