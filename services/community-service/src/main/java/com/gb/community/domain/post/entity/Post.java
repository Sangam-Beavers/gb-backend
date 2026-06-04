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
import java.util.UUID;
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

    /**
     * 댓글 삭제 시 카운터 캐시 정합 유지용 도메인 메서드(증가 메서드와 대칭).
     *
     * <p>음수 방지 가드를 둔다 — 동시 삭제 race나 시드 데이터 누락으로 0 상태에서 호출돼도
     * 음수로 떨어지지 않도록 0에서 멈춘다. 카운터는 캐시(약한 일관성)라 정합이 잠시 깨지는 것보다
     * 음수로 노출되는 것이 더 큰 문제다(클라이언트 표시 깨짐).
     */
    public void decreaseCommentCount() {
        if (this.commentCount > 0) {
            this.commentCount -= 1;
        }
    }

    /**
     * 게시글 작성용 정적 팩토리 (CLAUDE.md §4 — Request → Entity 변환은 정적 메서드).
     *
     * <p>{@code publicId}(UUID)는 서버가 생성하고, {@code language}는 인증/locale 연동 전이라 일단 "ko"로 고정한다.
     * TODO: 인증/locale 연동 후 작성자 언어를 채우도록 교체. 카운터 기본값(0)은 빌더가 채운다.
     *
     * <p>요청 DTO를 직접 import하지 않고 파싱된 값만 받는다 — category(String) → enum 변환·검증은
     * 서비스 책임이고, 엔티티가 dto/검증 예외에 의존하지 않도록 분리한다.
     */
    public static Post of(String userPublicId, PostCategory category, String title, String content) {
        return Post.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .category(category)
                .language("ko") // 빌더 필수값. 인증/locale 연동 전이라 "ko" 고정. (위 TODO 참고)
                .title(title)
                .content(content)
                .build();
    }

    /**
     * 게시글 부분 수정(PATCH) 도메인 메서드. {@code null} 인자는 "변경 없음"으로 보고 기존값을 유지한다.
     *
     * <p>{@code @Setter} 금지(CLAUDE.md §4)라 수정도 의미 있는 도메인 메서드로만 노출한다.
     * 빈 문자열을 "유지"로 볼지 "빈 값 설정"으로 볼지의 PATCH 해석은 서비스에서 처리하고
     * (blank → null 정규화), 여기서는 null 여부만 본다.
     */
    public void update(PostCategory category, String title, String content) {
        if (category != null) {
            this.category = category;
        }
        if (title != null) {
            this.title = title;
        }
        if (content != null) {
            this.content = content;
        }
    }
}
