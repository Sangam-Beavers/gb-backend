package com.gb.community.domain.like.entity;

/**
 * 좋아요 대상 유형. likes 테이블은 게시글/댓글 좋아요를 한 테이블로 통합하고
 * {@code target_type}으로 구분한다(docs/database.md §5).
 *
 * <p>현재 구현은 게시글 좋아요({@link #POST})만 사용한다. 댓글 좋아요({@link #COMMENT})는
 * likes 테이블 스키마(POST/COMMENT 통합)에 맞춰 값만 정의해 두고, 실제 사용은 댓글 좋아요 도입 시점에 한다.
 */
public enum LikeTargetType {
    /** 게시글 좋아요 (target_id = posts.id). */
    POST,
    /** 댓글 좋아요 (target_id = comments.id). 현재 미사용 — 스키마 SSOT 정합용 정의. */
    COMMENT
}
