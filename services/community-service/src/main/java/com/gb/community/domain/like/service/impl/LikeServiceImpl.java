package com.gb.community.domain.like.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.like.dto.response.LikedPostListResponse;
import com.gb.community.domain.like.dto.response.LikedPostSummaryResponse;
import com.gb.community.domain.like.dto.response.PostLikeResponse;
import com.gb.community.domain.like.entity.Like;
import com.gb.community.domain.like.entity.LikeTargetType;
import com.gb.community.domain.like.repository.LikeRepository;
import com.gb.community.domain.like.repository.LikedPostProjection;
import com.gb.community.domain.like.service.LikeService;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LikeServiceImpl implements LikeService {

    private final LikeRepository likeRepository;
    private final PostRepository postRepository;
    private final MemberClient memberClient;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LikedPostListResponse getLikedPosts(String userPublicId, String sort, int page, int size) {
        // MemberClient(외부 HTTP)를 readOnly tx/커넥션 보유 중 호출하지 않도록
        // NOT_SUPPORTED로 무트랜잭션 처리(repo 호출은 각자 짧은 readOnly tx, Post/Comment 목록과 동일 정책).
        // 정렬은 Repository JPQL의 ORDER BY로 고정하므로 Pageable에는 sort를 싣지 않는다(page/size만).
        Pageable pageable = PageRequest.of(page, size);
        Page<LikedPostProjection> result = switch (normalizeSort(sort)) {
            case "latest" -> likeRepository.findLikedPostsOrderByLikedAt(userPublicId, pageable);
            case "popular" -> likeRepository.findLikedPostsOrderByLikeCount(userPublicId, pageable);
            default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        };
        List<LikedPostProjection> rows = result.getContent();

        // 작성자 표시 정보를 배치로 1회 조회한다(N+1 회피). 중복 작성자는 distinct로 1회만.
        // getMembers는 요청한 모든 id를 키로 포함(누락=fallback)하므로 아래 .get(id)는 null이 되지 않는다.
        List<String> authorIds = rows.stream().map(row -> row.getPost().getUserPublicId()).distinct().toList();
        Map<String, MemberInfo> authorsByPublicId =
                authorIds.isEmpty() ? Map.of() : memberClient.getMembers(authorIds);

        List<LikedPostSummaryResponse> items = rows.stream()
                .map(row -> LikedPostSummaryResponse.from(
                        row.getPost(),
                        authorsByPublicId.get(row.getPost().getUserPublicId()),
                        row.getLikedAt()))
                .toList();

        return LikedPostListResponse.of(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Override
    @Transactional
    public PostLikeResponse like(String userPublicId, String postPublicId) {
        Post post = getActivePostOrThrow(postPublicId);

        // 1차: 이미 좋아요한 글이면 중복 → COMMON4091(흔한 경로를 DB 제약 없이 깔끔히 처리).
        if (likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                userPublicId, LikeTargetType.POST, post.getId())) {
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }

        try {
            // saveAndFlush로 INSERT를 즉시 실행해, 동시성 경쟁의 UNIQUE 위반을 이 메서드 안에서 잡는다.
            likeRepository.saveAndFlush(Like.ofPost(userPublicId, post.getId()));
        } catch (DataIntegrityViolationException e) {
            // 동시성 backstop: exists 검사 통과 후 경쟁 INSERT가 (user, POST, target) UNIQUE를 위반.
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }

        postRepository.incrementLikeCount(post.getId());
        // 벌크 UPDATE 직후 같은 트랜잭션에서 like_count를 재조회해 실제 저장값을 응답한다(동시 좋아요로
        // 인한 표시 오차 제거 — post.likeCount는 로드 시점 값이라 stale). 재조회가 비면(이론상 불가) +1 폴백.
        int likeCount = postRepository.findLikeCountById(post.getId())
                .orElse(post.getLikeCount() + 1);
        return PostLikeResponse.of(post.getPublicId(), likeCount, true);
    }

    @Override
    @Transactional
    public PostLikeResponse unlike(String userPublicId, String postPublicId) {
        Post post = getActivePostOrThrow(postPublicId);

        // 원자 삭제: 동시 중복 취소(같은 행을 두 요청이 함께 읽고 둘 다 감소)로 인한 like_count 과차감을
        // 막는다(COM-02). 벌크 DELETE의 영향행 수로 "실제로 지운" 1건만 골라낸다.
        int deleted = likeRepository.deleteByUserPublicIdAndTargetTypeAndTargetId(
                userPublicId, LikeTargetType.POST, post.getId());
        if (deleted == 0) {
            // 안 누른 글(또는 동시 취소에서 진 쪽)은 멱등하게 no-op으로 처리한다(like_count 변화 없음).
            return PostLikeResponse.of(post.getPublicId(), post.getLikeCount(), false);
        }

        postRepository.decrementLikeCount(post.getId()); // DB는 like_count > 0 가드(음수 방지)
        // 벌크 UPDATE 직후 재조회해 실제 저장값을 응답한다(동시 요청 표시 오차 제거). 재조회가 비면
        // (이론상 불가) 로드 시점 -1로 폴백하되 음수 방지.
        int likeCount = postRepository.findLikeCountById(post.getId())
                .orElse(Math.max(0, post.getLikeCount() - 1));
        return PostLikeResponse.of(post.getPublicId(), likeCount, false);
    }

    // ----- helpers -----

    /** 활성(미삭제) 게시글 조회. 없거나 삭제됐으면 COMMUNITY4001. */
    private Post getActivePostOrThrow(String postPublicId) {
        return postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));
    }

    /** sort 정규화. null/blank면 기본 latest, 그 외는 소문자 trim(검증은 호출 측 switch에서). */
    private String normalizeSort(String sortRaw) {
        return (sortRaw == null || sortRaw.isBlank()) ? "latest" : sortRaw.trim().toLowerCase();
    }
}