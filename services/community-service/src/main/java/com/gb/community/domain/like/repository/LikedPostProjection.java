package com.gb.community.domain.like.repository;

import com.gb.community.domain.post.entity.Post;
import java.time.LocalDateTime;

/**
 * 관심글 목록 조회 결과 행 — 게시글 + 좋아요 누른 시각(liked_at).
 *
 * <p>Spring Data 인터페이스 프로젝션. {@link LikeRepository}의 관심글 JPQL이 {@code SELECT p AS post,
 * l.createdAt AS likedAt} 별칭으로 반환하면, 별칭이 게터 이름({@code getPost}/{@code getLikedAt})에
 * 매핑된다. {@code getPost()}는 Post 엔티티 전체를 그대로 돌려주므로, 서비스에서 작성자 정보 조립과
 * DTO 변환을 기존 게시글 응답과 동일하게 처리할 수 있다.
 */
public interface LikedPostProjection {

    Post getPost();

    LocalDateTime getLikedAt();
}