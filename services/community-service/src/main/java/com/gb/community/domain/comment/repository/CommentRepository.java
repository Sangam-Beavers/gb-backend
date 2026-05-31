package com.gb.community.domain.comment.repository;

import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.post.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
