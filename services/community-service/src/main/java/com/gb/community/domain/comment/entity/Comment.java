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
 */
@Entity
@Getter
@Table(name = "comments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseSoftDeleteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
    private Comment(Post post, String userPublicId, Long parentId, String content) {
        this.post = post;
        this.userPublicId = userPublicId;
        this.parentId = parentId;
        this.content = content;
        this.likeCount = 0;
    }
}
