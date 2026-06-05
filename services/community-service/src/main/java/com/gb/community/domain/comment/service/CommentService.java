package com.gb.community.domain.comment.service;

import com.gb.community.domain.comment.dto.request.CreateCommentRequest;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.dto.response.CommentResponse;
import com.gb.community.domain.comment.entity.Comment;

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

    /**
     * 댓글 작성의 DB 본문(쓰기 트랜잭션). 활성 게시글 검증 → Comment INSERT → {@code comment_count} 원자
     * 증가까지를 한 트랜잭션으로 묶고, 저장된 {@link Comment}를 반환한다.
     *
     * <p><b>self-proxy 전용</b> — {@link #createComment}가 프록시를 통해 호출해야 {@code @Transactional}이
     * 적용된다(같은 빈 내부 직접 호출은 AOP 우회). 외부 MemberClient 호출(작성자 표시 정보)은 이 트랜잭션
     * <b>밖</b>(createComment)에서 한다 — 쓰기 tx + posts 행 락(incrementCommentCount)을 보유한 채 HTTP를
     * 기다리지 않기 위함(10D community-1, wallet registerAccountLocked와 동일 구조). 다른 컴포넌트에서
     * 직접 호출하지 말 것.
     */
    Comment createCommentTx(String postPublicId, String userPublicId, CreateCommentRequest request);

    /**
     * 댓글 삭제(soft delete). 본인이 작성한 댓글만 삭제 가능하며, 게시글의 {@code comment_count}를
     * 1 감소시킨다(같은 트랜잭션 내). 응답 본문은 없다(컨트롤러가 200 OK + data:null).
     *
     * <p>검증 순서(상위→하위): ① 게시글 활성 → COMMUNITY4001, ② 댓글 활성 → COMMUNITY4002,
     * ③ 댓글이 해당 게시글 소속인지(URL 일관성) → COMMUNITY4002,
     * ④ 본인 작성 여부 → COMMON4031. "이미 삭제된 댓글의 재삭제"는 ② 단계에서 자동 COMMUNITY4002로
     * 떨어진다(deleted_at IS NULL 필터).
     */
    void deleteComment(String postPublicId, String commentPublicId, String userPublicId);
}
