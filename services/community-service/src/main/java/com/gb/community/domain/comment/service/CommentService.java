package com.gb.community.domain.comment.service;

import com.gb.community.domain.comment.dto.response.CommentListResponse;

/**
 * 커뮤니티 댓글 조회 비즈니스 로직 (api-spec §7).
 *
 * <p>식별자 {@code postPublicId}는 게시글 UUID(public_id)다 — 내부 id는 노출/사용하지 않는다.
 * 본 PR 범위는 댓글 목록 조회만 — 작성/수정/삭제·대댓글·좋아요는 별도 이슈다.
 */
public interface CommentService {

    /**
     * 특정 게시글의 댓글 목록 조회. 작성순(오래된 순)으로 정렬해 페이지네이션한다.
     * 없거나 삭제된 게시글이면 COMMUNITY4001. 삭제된 댓글은 결과에서 제외한다.
     */
    CommentListResponse getComments(String postPublicId, int page, int size);
}
