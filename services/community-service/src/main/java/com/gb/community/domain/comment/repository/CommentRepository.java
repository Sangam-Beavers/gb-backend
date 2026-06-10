package com.gb.community.domain.comment.repository;

import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.post.entity.Post;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Comment 엔티티 Repository.
 *
 * <p>댓글 목록 조회(작성순 페이지네이션)를 제공한다. 모든 조회는 soft delete된 row
 * ({@code deleted_at IS NOT NULL})를 제외한다(CLAUDE.md §4, database.md §6 — comments는 soft delete 대상).
 *
 * <p>챗봇 MCP2는 이 Repository를 호출하지 않고 mcp_reader 계정으로 MySQL을 직접 SELECT한다
 * (ai-chatbot-mcp.md §9 — Post와 Comment를 JOIN해서 매칭) — 그 경로와는 무관.
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 특정 게시글의 활성(미삭제) 댓글을 페이지네이션해 조회한다.
     *
     * <p>정렬은 {@link Pageable}로 받는다(서비스가 작성순 = createdAt ASC, tie-break id ASC를 싣는다).
     * {@code post} 객체로 받아 {@code WHERE post_id = ? AND deleted_at IS NULL}을 생성한다 —
     * 서비스가 게시글 존재(미삭제)를 먼저 검증해 확보한 Post를 그대로 넘긴다.
     * count 쿼리는 Spring Data가 본 쿼리에서 자동 파생한다(단순 조건이라 안정적).
     */
    Page<Comment> findByPostAndDeletedAtIsNull(Post post, Pageable pageable);

    /**
     * publicId(UUID)로 활성(미삭제) 댓글 1건 조회. 댓글 삭제 API에서 사용.
     *
     * <p>{@code deleted_at IS NULL} 필터로 "이미 soft delete된 댓글의 재삭제"는 자동으로 빈 결과가 되어
     * 서비스에서 {@code COMMUNITY4002}로 변환된다(별도 분기 불필요). path의 postId와 댓글의 실제
     * post 일치 여부는 서비스 책임이라 여기선 검증하지 않는다(post 무관 단건 조회).
     */
    Optional<Comment> findByPublicIdAndDeletedAtIsNull(String publicId);

    /**
     * publicId(UUID) 댓글을 원자적으로 soft delete하고 영향받은 행 수를 반환한다(댓글 삭제 API).
     *
     * <p><b>동시 중복 삭제 가드(COM1 회귀):</b> {@code WHERE deleted_at IS NULL} 조건으로 활성 행 1건만
     * 전이시킨다. 같은 댓글을 동시에 삭제하는 두 트랜잭션은 행 락으로 직렬화돼 승자만 1, 패자는 0을 받는다.
     * 서비스는 반환값이 1일 때만 {@code comment_count}를 감소시켜 과차감을 막는다(post unlike의 affected-row
     * 게이트와 동일 패턴). 엔티티 {@code softDelete()}(행 가드 없는 dirty-update)는 동시 중복 삭제 시 둘 다
     * 통과해 과차감되므로 댓글 삭제 경로에선 이 메서드를 쓴다. 벌크 UPDATE라 영속성 컨텍스트의 Comment
     * 인스턴스는 갱신되지 않는다(삭제 후 미사용).
     */
    @Modifying
    @Query("UPDATE Comment c SET c.deletedAt = :now WHERE c.publicId = :publicId AND c.deletedAt IS NULL")
    int softDeleteByPublicId(@Param("publicId") String publicId, @Param("now") LocalDateTime now);

    /** 관리자용 특정 회원 댓글 조회 (최신순). */
    Page<Comment> findByUserPublicIdAndDeletedAtIsNull(String userPublicId, Pageable pageable);
}
