package com.gb.community.domain.post.repository;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /**
     * 스켈레톤부터 있던 단건 조회. soft delete된 글도 잡히므로 CRUD 흐름에서는 쓰지 않는다.
     * (삭제건 제외가 필요하면 {@link #findByPublicIdAndDeletedAtIsNull}을 쓴다.)
     */
    Optional<Post> findByPublicId(String publicId);

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
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.deletedAt IS NULL
              AND (:category IS NULL OR p.category = :category)
              AND (:keyword IS NULL
                   OR p.title LIKE CONCAT('%', :keyword, '%')
                   OR p.content LIKE CONCAT('%', :keyword, '%'))
            """)
    Page<Post> search(@Param("category") PostCategory category,
                       @Param("keyword") String keyword,
                       Pageable pageable);
}