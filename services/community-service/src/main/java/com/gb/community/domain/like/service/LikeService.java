package com.gb.community.domain.like.service;

import com.gb.community.domain.like.dto.response.LikedPostListResponse;
import com.gb.community.domain.like.dto.response.PostLikeResponse;

/**
 * 관심글(좋아요) 비즈니스 로직 (api-spec §4~§5).
 *
 * <p>식별자 {@code postPublicId}는 게시글 UUID(public_id)다 — 내부 id는 노출/사용하지 않는다.
 * {@code userPublicId}는 인증 미구현 동안 컨트롤러가 헤더로 임시 수신한 요청자 식별자다(CLAUDE.md §9).
 */
public interface LikeService {

    /**
     * 관심글 목록. sort(latest=좋아요 누른 시각순 / popular=좋아요 수순, 기본 latest)로 정렬하고
     * 페이지네이션한다. 잘못된 sort는 COMMON4001. 삭제된 글은 제외한다.
     */
    LikedPostListResponse getLikedPosts(String userPublicId, String sort, int page, int size);

    /**
     * 관심글 저장(좋아요). 없는/삭제된 글이면 COMMUNITY4001, 이미 좋아요면 COMMON4091(중복).
     * 성공 시 like_count +1.
     */
    PostLikeResponse like(String userPublicId, String postPublicId);

    /**
     * 관심글 취소(좋아요 취소). 없는/삭제된 글이면 COMMUNITY4001. 좋아요가 있으면 삭제 + like_count -1,
     * 안 누른 글이면 멱등 no-op으로 처리한다.
     */
    PostLikeResponse unlike(String userPublicId, String postPublicId);
}
