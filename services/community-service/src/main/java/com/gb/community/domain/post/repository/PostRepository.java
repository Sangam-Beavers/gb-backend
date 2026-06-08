package com.gb.community.domain.post.repository;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Post 엔티티 Repository.
 *
 * <p>게시글 CRUD에 필요한 검색·페이지네이션·단건 조회 메서드를 제공한다. 모든 조회는
 * soft delete된 row({@code deleted_at IS NOT NULL})를 제외한다(CLAUDE.md §4, database.md §5).
 *
 * <p>챗봇 MCP2는 이 Repository를 호출하지 않고 mcp_reader 계정으로 MySQL을
 * 직접 SELECT한다 (ai-chatbot-mcp.md §9) — 그 경로와는 무관.
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    /** 활성(미삭제) 게시글 단건 조회. 단건 조회/수정/삭제 흐름에서 사용한다. */
    Optional<Post> findByPublicIdAndDeletedAtIsNull(String publicId);

    /**
     * 게시글 목록·검색. 활성(미삭제) 글만 대상으로 한다.
     *
     * <ul>
     *   <li>{@code category} — null이면 전체 카테고리, 값 있으면 해당 카테고리만.</li>
     *   <li>{@code keyword} — null/빈 문자열이면 검색 안 함(전체), 있으면 제목·본문 부분일치(LIKE).</li>
     *   <li>정렬·페이지는 {@link Pageable}로 받는다(서비스에서 sort 파라미터를 Sort로 변환).</li>
     * </ul>
     *
     * <p>count 쿼리는 Spring Data가 본 쿼리에서 자동 파생한다.
     *
     * <p>{@code keyword}는 LIKE 메타문자(%, _)를 와일드카드가 아닌 literal로 매칭해야 하므로
     * {@code ESCAPE '|'}를 지정한다. 호출 측(서비스)이 {@code | % _}를 이스케이프한 값을 넘긴다.
     *
     * <p>이스케이프 문자로 백슬래시(\) 대신 파이프(|)를 쓴 이유: Hibernate가 {@code ESCAPE '\'}를
     * SQL에 {@code escape '\'}(작은따옴표 안 백슬래시 1개)로 렌더링하는데, MySQL은 문자열 리터럴에서
     * 백슬래시를 이스케이프로 처리(기본값)해 리터럴이 깨진다(H2 MySQL 모드는 통과해 가려짐). 파이프는
     * 어떤 DB의 문자열 리터럴에서도 특수문자가 아니라 H2/MySQL 모두에서 동일하게 안전하다.
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.deletedAt IS NULL
              AND (:category IS NULL OR p.category = :category)
              AND (:keyword IS NULL
                   OR p.title LIKE CONCAT('%', :keyword, '%') ESCAPE '|'
                   OR p.content LIKE CONCAT('%', :keyword, '%') ESCAPE '|')
            """)
    Page<Post> search(@Param("category") PostCategory category,
                       @Param("keyword") String keyword,
                       Pageable pageable);

    /**
     * 좋아요 수 캐시({@code like_count}) 원자적 +1 (관심글 저장 시).
     *
     * <p>{@code like_count}는 likes 테이블 집계의 denormalized 캐시다. 엔티티 read-modify-write는
     * 동시 좋아요에서 lost update가 날 수 있어, DB에서 원자적으로 증가시킨다(읽고-쓰기 경합 방지).
     * 벌크 UPDATE라 영속성 컨텍스트를 우회하므로, 같은 트랜잭션에서 로드해 둔 Post 인스턴스의
     * {@code likeCount}는 갱신되지 않는다(응답 수치는 호출 측에서 보정).
     */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    /**
     * 좋아요 수 캐시({@code like_count}) 원자적 -1 (관심글 취소 시).
     * {@code like_count > 0} 가드로 음수로 내려가지 않게 막는다(취소 멱등 처리와 함께 정합 유지).
     */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount - 1 WHERE p.id = :id AND p.likeCount > 0")
    void decrementLikeCount(@Param("id") Long id);

    /**
     * 댓글 수 캐시({@code comment_count}) 원자적 +1 (댓글 작성 시). 엔티티 RMW의 lost update를 피해
     * DB에서 원자적으로 증가시킨다({@link #incrementLikeCount}와 동일 패턴). 벌크 UPDATE라 1차 캐시의
     * Post 인스턴스 {@code commentCount}는 갱신되지 않으나, 응답에 댓글 수를 싣지 않아 보정 불필요.
     */
    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + 1 WHERE p.id = :id")
    void incrementCommentCount(@Param("id") Long id);

    /**
     * 댓글 수 캐시({@code comment_count}) 원자적 -1 (댓글 삭제 시).
     * {@code comment_count > 0} 가드로 음수로 내려가지 않게 막는다.
     */
    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount - 1 WHERE p.id = :id AND p.commentCount > 0")
    void decrementCommentCount(@Param("id") Long id);

    /**
     * like_count 캐시의 현재 저장값 단건 조회.
     *
     * <p>{@link #incrementLikeCount}/{@link #decrementLikeCount} 같은 벌크 UPDATE 직후, 같은 트랜잭션에서
     * 갱신된 실제 like_count를 응답에 싣기 위해 쓴다. 스칼라 프로젝션이라 1차 캐시의 stale 엔티티가 아니라
     * DB 최신값을 읽으므로(동시 좋아요/취소로 인한 표시 수치 오차 제거), 엔티티 재로딩(findById)으로는
     * 1차 캐시의 옛 likeCount가 나와 효과가 없다.
     */
    @Query("SELECT p.likeCount FROM Post p WHERE p.id = :id")
    Optional<Integer> findLikeCountById(@Param("id") Long id);

    /**
     * 주요 QnA 목록 조회 (api-spec §8) — 특정 카테고리의 활성 게시글을 답변(댓글) 수 내림차순으로
     * 상위 N건 반환한다. 페이지네이션 메타 없는 고정 N건 목록(Top N) 용도다.
     *
     * <p>정렬: {@code comment_count DESC, id DESC}. comment_count 동률에서 최근 글이 위로 오도록
     * id DESC를 tie-breaker로 둔다(id는 외부 노출 X, 정렬 키로만 사용 — conventions §5).
     *
     * <p>입력의 size 가드는 서비스 책임이며, 여기는 호출 측이 만든 {@link Pageable}을 그대로 사용한다.
     * count 쿼리는 Top N 용도라 호출하지 않도록 {@link Page} 대신 {@link java.util.List}를 반환한다.
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.deletedAt IS NULL
              AND p.category = :category
            ORDER BY p.commentCount DESC, p.id DESC
            """)
    java.util.List<Post> findTopByCategoryOrderByCommentCountDesc(
            @Param("category") PostCategory category, Pageable pageable);

    // ===== Admin internal API =====

    /**
     * 관리자용 신고 게시글 페이지 — 현재 본체에 신고 테이블이 없어 발표용 프록시로
     * comment_count DESC 순 활성 게시글을 반환한다(향후 reports 테이블 도입 시 교체).
     * category 필터 옵션.
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.deletedAt IS NULL
              AND (:category IS NULL OR p.category = :category)
            """)
    org.springframework.data.domain.Page<Post> findReportableForAdmin(
            @Param("category") PostCategory category,
            Pageable pageable);

    long countByDeletedAtIsNull();
}