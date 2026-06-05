package com.gb.community.domain.comment.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.comment.dto.request.CreateCommentRequest;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.dto.response.CommentResponse;
import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.comment.service.CommentService;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final MemberClient memberClient;

    /** self-injection: createCommentTx의 @Transactional 프록시 적용 위함(wallet 충전/송금/등록과 동일 패턴). */
    @Autowired
    @Lazy
    private CommentService self;

    // 10D community-1/2 — MemberClient(외부 HTTP) 호출은 트랜잭션/커넥션을 보유한 채 하지 않는다.
    //   현재는 MockMemberClient(인메모리)뿐이라 latent지만, RealMemberClient 도입 시 tx·행 락 보유 중
    //   네트워크 대기(풀 고갈·락 직렬화)가 되므로 선제 분리해 둔다. 읽기 경로는 NOT_SUPPORTED(단일 SELECT는
    //   트랜잭션 불요 — repo 호출이 각자 짧은 readOnly tx), 쓰기 경로는 DB 본문을 self-proxy tx 메서드로 묶고
    //   회원 조회는 커밋 후 응답 조립에서 한다.

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommentListResponse getComments(String postPublicId, int page, int size) {
        // 댓글 목록은 게시글에 종속된다 — 없거나 삭제된 게시글이면 404 COMMUNITY4001
        // (단건 조회/좋아요/댓글 작성 등 게시글 종속 API와 동일 정책). 빈 목록과 구분한다.
        Post post = getActivePostOrThrow(postPublicId);

        // 작성순(오래된 순) = createdAt ASC. 동률은 id ASC를 tie-breaker로 둬 정렬을 결정적으로 만든다
        // (id는 노출 X, 정렬 키로만 사용).
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        Page<Comment> result = commentRepository.findByPostAndDeletedAtIsNull(post, pageable);
        List<Comment> comments = result.getContent();

        // 작성자 표시 정보를 배치로 1회 조회한다(N+1 회피). 중복 작성자는 distinct로 1회만.
        // getMembers는 요청한 모든 id를 키로 포함(누락=fallback)하므로 아래 .get(id)는 null이 되지 않는다.
        List<String> authorIds = comments.stream().map(Comment::getUserPublicId).distinct().toList();
        Map<String, MemberInfo> authorsByPublicId =
                authorIds.isEmpty() ? Map.of() : memberClient.getMembers(authorIds);

        List<CommentResponse> items = comments.stream()
                .map(comment -> CommentResponse.from(
                        comment,
                        authorsByPublicId.get(comment.getUserPublicId()),
                        post.getPublicId()))
                .toList();

        return CommentListResponse.of(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommentResponse createComment(String postPublicId, String userPublicId,
                                         CreateCommentRequest request) {
        // DB 본문(검증→INSERT→comment_count 증가)은 self-proxy 쓰기 트랜잭션으로 묶고, 외부 MemberClient
        // 호출은 커밋 "후" 응답 조립에서 한다 — 쓰기 tx + posts 행 락(incrementCommentCount)을 보유한 채
        // HTTP를 기다리지 않는다(10D community-1). NOT_SUPPORTED로 클래스 readOnly tx도 차단해 이 메서드
        // 전체가 무트랜잭션임을 명시한다(wallet TransferServiceImpl.execute와 동일 구조).
        Comment comment = self.createCommentTx(postPublicId, userPublicId, request);

        // 작성자 표시 정보(닉네임/인증배지) MemberClient로 조회 — tx 밖.
        //   MockMemberClient는 미존재 시 fallback "Unknown" 반환하므로 null 우려 없음.
        //   실서비스 RealMemberClient 도입 후 HTTP 장애 시 BusinessException 가능 — 그 경우에도 댓글
        //   INSERT는 이미 커밋돼 보존된다(표시 정보 실패가 본문 쓰기를 롤백하지 않음).
        MemberInfo author = memberClient.getMember(userPublicId);

        // postPublicId는 URL 경로의 식별자 그대로 — createCommentTx가 같은 값으로 활성 글을 검증했다.
        return CommentResponse.from(comment, author, postPublicId);
    }

    @Override
    @Transactional
    public Comment createCommentTx(String postPublicId, String userPublicId,
                                   CreateCommentRequest request) {
        // (1) 활성 게시글 조회 — 없거나 삭제됐으면 COMMUNITY4001(목록 조회와 동일 정책).
        Post post = getActivePostOrThrow(postPublicId);

        // (2) Comment INSERT — 본 사이클은 최상위 댓글만(parentId=null). 대댓글은 별도 이슈.
        //     public_id는 builder가 자동 생성(Comment.builder 주석 참고), like_count=0.
        Comment comment = commentRepository.save(Comment.builder()
                .post(post)
                .userPublicId(userPublicId)
                .parentId(null)
                .content(request.getContent())
                .build());

        // (3) 게시글 comment_count +1 — 동시 작성 lost update 방지를 위해 DB 원자 UPDATE(like_count와 동일).
        //     이 행 락은 커밋까지 유지되므로 critical section엔 DB 작업만 둔다(외부 호출 금지).
        postRepository.incrementCommentCount(post.getId());

        return comment;
    }

    @Override
    @Transactional
    public void deleteComment(String postPublicId, String commentPublicId, String userPublicId) {
        // (1) 활성 게시글 조회 — 없거나 삭제됐으면 COMMUNITY4001(목록·작성과 동일 정책).
        //     게시글이 삭제된 상태에서 댓글만 만지는 건 의미 없으므로 상위 자원부터 검증.
        Post post = getActivePostOrThrow(postPublicId);

        // (2) 활성 댓글 조회 — 없거나 이미 soft delete된 경우 COMMUNITY4002.
        //     이미 삭제된 댓글의 재삭제는 deleted_at IS NULL 필터로 자동 404가 된다(별도 분기 없음).
        Comment comment = commentRepository.findByPublicIdAndDeletedAtIsNull(commentPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND));

        // (3) URL 일관성 검증 — path의 postId와 댓글의 실제 post가 일치해야 한다.
        //     불일치는 권한 문제가 아니라 "이 게시글에는 그런 댓글이 없음"이므로 COMMUNITY4002로 통일.
        //     내부 id로 비교(post는 LAZY지만 id 접근은 proxy 초기화 없이 가능).
        if (!comment.getPost().getId().equals(post.getId())) {
            throw new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND);
        }

        // (4) 본인 작성 여부 검증 — 본인만 삭제 가능. 타인 댓글이면 COMMON4031.
        if (!comment.getUserPublicId().equals(userPublicId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // (5) 원자 조건부 soft delete — deleted_at IS NULL 행만 1건 전이시키고 영향행 수를 받는다.
        //     같은 댓글을 동시에 삭제하는 두 요청은 행 락으로 직렬화돼 패자는 affected==0을 받는다. 반환값이
        //     1일 때만 comment_count를 감소시켜 과차감을 막는다(post unlike의 affected-row 게이트 미러링, COM1
        //     회귀). entity softDelete()는 행 가드가 없어 동시 중복 삭제 시 둘 다 통과·둘 다 -1 되므로 쓰지 않는다.
        int affected = commentRepository.softDeleteByPublicId(commentPublicId, LocalDateTime.now(ZoneOffset.UTC));
        if (affected == 0) {
            return; // 다른 트랜잭션이 이미 삭제(멱등 no-op) — comment_count를 감소시키지 않는다.
        }
        postRepository.decrementCommentCount(post.getId());
    }

    // ----- helpers -----

    /** 활성(미삭제) 게시글 조회. 없거나 삭제됐으면 COMMUNITY4001. */
    private Post getActivePostOrThrow(String postPublicId) {
        return postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));
    }
}
