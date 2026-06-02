package com.gb.community.domain.comment.service;

import com.gb.community.domain.comment.dto.request.CreateCommentRequest;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.dto.response.CommentResponse;

/**
 * 커뮤니티 댓글 비즈니스 로직 (api-spec §6·§7).
 *
 * <p>식별자 {@code postPublicId}는 게시글 UUID(public_id)다 — 내부 id는 노출/사용하지 않는다.
 * 작성/조회 메서드를 노출하며, 수정/삭제·대댓글·좋아요는 별도 이슈다.
 */
public interface CommentService {

    /**
     * 특정 게시글의 댓글 목록 조회. 작성순(오래된 순)으로 정렬해 페이지네이션한다.
     * 없거나 삭제된 게시글이면 COMMUNITY4001. 삭제된 댓글은 결과에서 제외한다.
     */
    CommentListResponse getComments(String postPublicId, int page, int size);

    /**
     * 댓글 작성. {@code postPublicId}의 게시글에 본인({@code userPublicId}) 명의로 1건 INSERT하고
     * 게시글의 {@code comment_count}를 1 증가시킨다(같은 트랜잭션 내).
     *
     * <p>대댓글은 본 사이클 범위 밖 — 모든 댓글이 최상위로 INSERT된다(Comment.parentId=null).
     * 게시글 없음/삭제됨은 {@code COMMUNITY4001}.
     *
     * <p>작성자 표시 정보(닉네임/인증배지)는 응답 조립 시 MemberClient로 조회해 채운다 — DB 직접
     * SELECT는 MSA 경계 위반(CLAUDE.md §7).
     */
    CommentResponse createComment(String postPublicId, String userPublicId, CreateCommentRequest request);
}
