package com.gb.community.domain.comment.service.impl;

import com.gb.common.exception.BusinessException;
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

    // ----- helpers -----

    /** 활성(미삭제) 게시글 조회. 없거나 삭제됐으면 COMMUNITY4001. */
    private Post getActivePostOrThrow(String postPublicId) {
        return postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));
    }
}
