package com.gb.community.domain.comment.entity;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.global.common.entity.BaseSoftDeleteEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 커뮤니티 댓글 엔티티 — <b>최소 스켈레톤</b>.
 *
 * <p>docs/database.md §4 comments 테이블 SSOT 그대로. CRUD API는 본 PR 범위 밖
 * (커뮤팀이 본격 작업 시작 시점에 controller/service/dto 추가).
 *
 * <p>여기는 챗봇 MCP2(`search_community_posts`)가 mcp_reader 계정으로
 * 직접 SELECT할 데이터의 생성 기반. 시나리오 ④ "비슷한 경험 한 사람?" 응답이
 * 풍부해지려면 본문뿐 아니라 댓글까지 매칭돼야 한다.
 *
 * <p>회원 참조: {@code user_public_id}는 member 도메인 users.public_id 논리 참조
 * (CLAUDE.md §7 — MSA 경계 넘는 회원 참조는 UUID, 물리 FK 금지).
 *
 * <p>Post 참조: 같은 community 스키마 내부라 {@link ManyToOne}(LAZY, 단방향) 매핑
 * (CLAUDE.md §4 Entity 규칙).
 *
 * <p>{@code public_id}(UUID): 댓글의 대외 식별자. 댓글 목록/작성 응답이 댓글을 {@code public_id}로
 * 노출하므로(api-spec §6·§7, CLAUDE.md §5 — 내부 id 노출 금지) posts와 동일하게 보유한다.
 * database.md §5 comments 표에는 컬럼이 누락돼 있으나, 같은 문서 §0·§6 공통 규약("외부 노출 식별자는
 * public_id (UUID, VARCHAR(36))")과 api-spec이 SSOT다 — 표의 누락은 보강 대상이라 Post와 일관되게 추가.
 */
@Entity
@Getter
@Table(name = "comments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseSoftDeleteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자 (UUID). conventions §0 — 내부 id는 응답/URL에 노출 금지. (Post.publicId와 동일 규칙) */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /** 동일 스키마 내부 참조 — JPA 객체 매핑 OK (CLAUDE.md §4). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    /** 작성자 (member-service users.public_id 논리 참조). */
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    /**
     * 대댓글 부모 ID. NULL이면 최상위 댓글, 값 있으면 대댓글.
     *
     * <p>자기참조 FK라 객체 매핑(@ManyToOne Comment)도 가능하지만,
     * 현재 시드/데모 범위에선 대댓글을 만들지 않아 원시 Long으로 둔다.
     *
     * <p>TODO: 대댓글 기능 도입 시 {@code @ManyToOne(fetch = LAZY) Comment parent}로 교체.
     */
    @Column(name = "parent_id")
    private Long parentId;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount;

    @Builder
    private Comment(String publicId, Post post, String userPublicId, Long parentId, String content) {
        // publicId가 없으면 UUID 자동 생성. Post는 of() 팩토리에서 생성하지만(빌더는 명시 요구),
        // 댓글은 본 PR이 읽기 전용이라 create 팩토리가 없고 빌더를 직접 쓰는 호출(시드/테스트)이 많아
        // NOT NULL 불변식을 빌더에서 보장한다. create API 도입 시 of() 추가 시점에 정책 재정렬.
        this.publicId = (publicId != null) ? publicId : UUID.randomUUID().toString();
        this.post = post;
        this.userPublicId = userPublicId;
        this.parentId = parentId;
        this.content = content;
        this.likeCount = 0;
    }
}
