package com.gb.community.domain.like.repository;

import com.gb.community.domain.like.entity.Like;
import com.gb.community.domain.like.entity.LikeTargetType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Like 엔티티 Repository — 좋아요 저장/취소/존재 검사와 관심글 목록 조회를 제공한다.
 *
 * <p>관심글 목록은 likes와 posts를 {@code target_id = posts.id}로 잇는 theta join이다(likes.target_id가
 * 다형 참조라 엔티티 연관이 없어 명시적 join 조건이 필요). 삭제된 글({@code deleted_at IS NOT NULL})은
 * 제외하고(CLAUDE.md §4), {@code target_type=POST} 행만 본다(댓글 좋아요는 제외).
 *
 * <p>정렬은 sort 파라미터에 따라 메서드를 나눠 JPQL의 {@code ORDER BY}로 고정한다(서비스는 Sort 없는
 * Pageable로 page/size만 넘긴다). 동률 시 결정적 순서를 위해 {@code l.createdAt}·{@code p.id}를
 * tie-breaker로 둔다. count 쿼리는 프로젝션·theta join 자동 파생이 불안정해 명시한다.
 */
public interface LikeRepository extends JpaRepository<Like, Long> {

    /** 중복 좋아요 검사 — 같은 회원이 같은 대상에 이미 좋아요를 눌렀는지. */
    boolean existsByUserPublicIdAndTargetTypeAndTargetId(
            String userPublicId, LikeTargetType targetType, Long targetId);

    /** 좋아요 취소 시 삭제 대상 조회. 없으면 멱등 no-op으로 처리한다. */
    Optional<Like> findByUserPublicIdAndTargetTypeAndTargetId(
            String userPublicId, LikeTargetType targetType, Long targetId);

    /**
     * 좋아요 취소 — 원자 삭제. 단일 벌크 DELETE의 JDBC 영향행 수를 반환해, 동시 중복 취소에서도
     * <b>실제로 행을 지운 1건만</b> like_count를 감소시키도록 호출 측에서 게이트하게 한다(COM-02).
     *
     * <p>파생 {@code deleteBy...}는 select-then-delete라 영향행이 <i>조회된</i> 엔티티 기준이라,
     * 두 요청이 같은 행을 동시에 읽으면 둘 다 1을 반환해 과차감을 못 막는다. {@code @Modifying} 벌크
     * DELETE는 행 락으로 직렬화되어 이긴 쪽 1, 진 쪽 0을 반환하므로 정확히 한 번만 감소한다
     * ({@link com.gb.community.domain.post.repository.PostRepository#decrementLikeCount}와 동일한 원자 갱신 패턴).
     */
    @Modifying
    @Query("DELETE FROM PostLike l WHERE l.userPublicId = :userPublicId "
            + "AND l.targetType = :targetType AND l.targetId = :targetId")
    int deleteByUserPublicIdAndTargetTypeAndTargetId(
            @Param("userPublicId") String userPublicId,
            @Param("targetType") LikeTargetType targetType,
            @Param("targetId") Long targetId);

    /**
     * 관심글 목록 — latest(좋아요 누른 시각순). 삭제글 제외, POST 대상만.
     * tie-break: 같은 시각이면 p.id DESC로 결정적 정렬.
     */
    @Query(value = "SELECT p AS post, l.createdAt AS likedAt FROM PostLike l, Post p "
            + "WHERE l.targetType = com.gb.community.domain.like.entity.LikeTargetType.POST "
            + "AND l.targetId = p.id AND l.userPublicId = :userPublicId AND p.deletedAt IS NULL "
            + "ORDER BY l.createdAt DESC, p.id DESC",
            countQuery = "SELECT count(l) FROM PostLike l, Post p "
                    + "WHERE l.targetType = com.gb.community.domain.like.entity.LikeTargetType.POST "
                    + "AND l.targetId = p.id AND l.userPublicId = :userPublicId AND p.deletedAt IS NULL")
    Page<LikedPostProjection> findLikedPostsOrderByLikedAt(
            @Param("userPublicId") String userPublicId, Pageable pageable);

    /**
     * 관심글 목록 — popular(좋아요 수순). 삭제글 제외, POST 대상만.
     * tie-break: 같은 like_count면 좋아요 누른 시각 DESC → p.id DESC로 결정적 정렬.
     */
    @Query(value = "SELECT p AS post, l.createdAt AS likedAt FROM PostLike l, Post p "
            + "WHERE l.targetType = com.gb.community.domain.like.entity.LikeTargetType.POST "
            + "AND l.targetId = p.id AND l.userPublicId = :userPublicId AND p.deletedAt IS NULL "
            + "ORDER BY p.likeCount DESC, l.createdAt DESC, p.id DESC",
            countQuery = "SELECT count(l) FROM PostLike l, Post p "
                    + "WHERE l.targetType = com.gb.community.domain.like.entity.LikeTargetType.POST "
                    + "AND l.targetId = p.id AND l.userPublicId = :userPublicId AND p.deletedAt IS NULL")
    Page<LikedPostProjection> findLikedPostsOrderByLikeCount(
            @Param("userPublicId") String userPublicId, Pageable pageable);
}