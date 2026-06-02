package com.gb.community.domain.comment.service.impl;

import com.gb.common.exception.BusinessException;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final MemberClient memberClient;

    @Override
    public CommentListResponse getComments(String postPublicId, int page, int size) {
        // 댓글 목록은 게시글에 종속된다 — 없거나 삭제된 게시글이면 404 COMMUNITY4001
        // (단건 조회/좋아요/댓글 작성 등 게시글 종속 API와 동일 정책). 빈 목록과 구분한다.
        Post post = getActivePostOrThrow(postPublicId);

        // 작성순(오래된 순) = createdAt ASC. 동률은 id ASC를 tie-breaker로 둬 정렬을 결정적으로 만든다
        // (id는 노출 X, 정렬 키로만 사용).
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        Page<Comment> result = commentRepository.findByPostAndDeletedAtIsNull(post, pageable);
        List<Comment> comments = result.getContent();

        // 작성자 표시 정보 조립. 같은 페이지 안의 중복 작성자는 1회만 조회한다(N+1 회피).
        // TODO: member-service 도입 시 건별 호출(N+1)을 batch 조회 API(예: GET /members?ids=...)로 교체.
        Map<String, MemberInfo> authorsByPublicId = comments.stream()
                .map(Comment::getUserPublicId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), memberClient::getMember));

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
    @Transactional
    public CommentResponse createComment(String postPublicId, String userPublicId,
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

        // (3) 게시글 comment_count +1 — dirty checking으로 UPDATE(같은 트랜잭션이라 자동 반영).
        post.increaseCommentCount();

        // (4) 작성자 표시 정보(닉네임/인증배지) MemberClient로 조회.
        //     MockMemberClient는 미존재 시 fallback "Unknown" 반환하므로 null 우려 없음.
        //     실서비스 RealMemberClient 도입 후엔 HTTP 장애 시 BusinessException 가능.
        MemberInfo author = memberClient.getMember(userPublicId);

        return CommentResponse.from(comment, author, post.getPublicId());
    }

    // ----- helpers -----

    /** 활성(미삭제) 게시글 조회. 없거나 삭제됐으면 COMMUNITY4001. */
    private Post getActivePostOrThrow(String postPublicId) {
        return postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));
    }
}
