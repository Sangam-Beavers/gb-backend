package com.gb.community.domain.like.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 게시글/댓글 좋아요(관심글) 엔티티 — docs/database.md §5 {@code likes} 테이블 SSOT.
 *
 * <p><b>BaseEntity/BaseSoftDeleteEntity를 상속하지 않는다.</b> likes 테이블은 {@code created_at} 1개만
 * 가지며 {@code updated_at}/{@code deleted_at}이 없다(좋아요는 수정·soft delete 없이 INSERT/DELETE만).
 * 그래서 공통 슈퍼클래스 대신 자체 {@code @CreatedDate} 컬럼 + {@code @EntityListeners}로 둔다
 * (Auditing은 {@code JpaConfig}의 {@code @EnableJpaAuditing}으로 이미 활성).
 *
 * <p>{@code target_id}는 다형 참조다: {@code target_type=POST}면 {@code posts.id},
 * {@code COMMENT}면 {@code comments.id}를 가리킨다. 가리키는 대상이 type에 따라 달라 단일 엔티티로
 * {@code @ManyToOne} 매핑할 수 없으므로 원시 {@code Long}으로 둔다(관심글 조회는 Repository의
 * theta join으로 Post와 연결).
 *
 * <p>중복 방지: {@code (user_public_id, target_type, target_id)} 복합 UNIQUE(docs §5).
 * 회원 참조는 MSA 경계를 넘으므로 {@code user_public_id}(UUID) 논리 참조다(CLAUDE.md §7).
 */
// entity name을 "PostLike"로 둔다: 클래스명 Like(파일/팩토리 네이밍 유지)를 JPQL에 그대로 쓰면
// FROM 절의 "Like"가 HQL 예약어 LIKE와 충돌해 Hibernate 6 파서가 깨진다. JPQL 엔티티명만 분리하면
// 자바 타입(Like)·테이블(likes)·파생 쿼리는 그대로 두고 @Query JPQL에서만 PostLike로 참조한다.
@Entity(name = "PostLike")
@Getter
@Table(
        name = "likes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_likes_user_target",
                columnNames = {"user_public_id", "target_type", "target_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Like {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 좋아요 누른 회원 (member-service users.public_id 논리 참조). */
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 10, nullable = false)
    private LikeTargetType targetType;

    /** 대상 ID — POST면 posts.id, COMMENT면 comments.id(다형 참조라 @ManyToOne 매핑 안 함). */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Like(String userPublicId, LikeTargetType targetType, Long targetId) {
        this.userPublicId = userPublicId;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    /**
     * 게시글 좋아요 생성 정적 팩토리 (CLAUDE.md §4 — 변환/생성은 정적 메서드).
     * {@code targetType}을 {@link LikeTargetType#POST}로 고정하고 {@code targetId = posts.id}로 둔다.
     */
    public static Like ofPost(String userPublicId, Long postId) {
        return Like.builder()
                .userPublicId(userPublicId)
                .targetType(LikeTargetType.POST)
                .targetId(postId)
                .build();
    }
}