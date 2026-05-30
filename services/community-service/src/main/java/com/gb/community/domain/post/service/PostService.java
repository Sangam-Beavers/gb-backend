package com.gb.community.domain.post.service;

import com.gb.community.domain.post.dto.request.PostCreateRequest;
import com.gb.community.domain.post.dto.request.PostUpdateRequest;
import com.gb.community.domain.post.dto.response.PostDetailResponse;
import com.gb.community.domain.post.dto.response.PostListResponse;

/**
 * 커뮤니티 게시글 CRUD 비즈니스 로직 (api-spec §1~§3).
 *
 * <p>식별자 {@code postPublicId}는 게시글 UUID(public_id)다 — 내부 id는 노출/사용하지 않는다.
 * {@code requesterUserPublicId}는 인증 미구현 동안 컨트롤러가 헤더로 임시 수신한 요청자 식별자다.
 */
public interface PostService {

    /**
     * 게시글 목록·검색. category(선택)·keyword(선택, 제목·본문 LIKE)·sort(latest/popular/accuracy)로
     * 필터·정렬하고 페이지네이션한다. 잘못된 category/sort는 COMMON4001로 던진다. 삭제글은 제외.
     */
    PostListResponse getPosts(String category, String keyword, String sort, int page, int size);

    /** 게시글 단건 조회. 없거나 삭제된 글이면 COMMUNITY4001. */
    PostDetailResponse getPost(String postPublicId);

    /** 게시글 작성. 작성자는 요청자(userPublicId), 잘못된 category는 COMMON4001. */
    PostDetailResponse createPost(String requesterUserPublicId, PostCreateRequest request);

    /**
     * 게시글 부분 수정(PATCH). 본인 글만 수정 가능(타인 → COMMON4031), 없는 글 → COMMUNITY4001,
     * 잘못된 category → COMMON4001. 보낸 필드만 변경한다.
     */
    PostDetailResponse updatePost(String requesterUserPublicId, String postPublicId, PostUpdateRequest request);

    /** 게시글 삭제(soft delete). 본인 글만(타인 → COMMON4031), 없는 글 → COMMUNITY4001. */
    void deletePost(String requesterUserPublicId, String postPublicId);
}