package com.gb.community.domain.like.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.like.dto.response.LikedPostListResponse;
import com.gb.community.domain.like.dto.response.PostLikeResponse;
import com.gb.community.domain.like.entity.Like;
import com.gb.community.domain.like.entity.LikeTargetType;
import com.gb.community.domain.like.repository.LikeRepository;
import com.gb.community.domain.like.repository.LikedPostProjection;
import com.gb.community.domain.like.service.impl.LikeServiceImpl;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * {@link LikeServiceImpl} 단위 테스트(Mockito). DB·Spring 컨텍스트 없이 조합/검증/예외/멱등을 본다.
 *
 * <p>like_count는 벌크 UPDATE(increment/decrement)로 DB에서만 바뀌므로, 응답 수치는 벌크 UPDATE 직후
 * 재조회한 실제 저장값이다 — 그 재조회 반영과 증감 호출 여부(verify)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock private LikeRepository likeRepository;
    @Mock private PostRepository postRepository;
    @Mock private MemberClient memberClient;
    @InjectMocks private LikeServiceImpl service;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";
    private static final String PID = "post-uuid-1";
    private static final Long POST_ID = 1L;

    private static final MemberInfo MINH = new MemberInfo("Minh", true);

    // ----- like -----

    @Test
    @DisplayName("좋아요 성공: Like 저장 + like_count +1, 응답은 재조회한 실제 저장값(동시 좋아요로 DB가 앞서가도 반영)")
    void like_정상() {
        Post post = post(USER, 5);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, POST_ID)).willReturn(false);
        // 로드 시점 likeCount=5라 in-memory 보정은 6이지만, 동시 좋아요로 DB가 8까지 오른 상황을 가정.
        given(postRepository.findLikeCountById(POST_ID)).willReturn(Optional.of(8));

        PostLikeResponse res = service.like(USER, PID);

        assertThat(res.getPostPublicId()).isEqualTo(post.getPublicId());
        assertThat(res.getLikeCount()).isEqualTo(8); // in-memory +1(=6)이 아니라 재조회한 실제 저장값
        assertThat(res.isLiked()).isTrue();
        verify(likeRepository).saveAndFlush(any(Like.class));
        verify(postRepository).incrementLikeCount(POST_ID);
    }

    @Test
    @DisplayName("좋아요 중복: 이미 좋아요면 COMMON4091, 저장·증가 호출 없음")
    void like_중복() {
        Post post = post(USER, 5);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, POST_ID)).willReturn(true);

        assertThatThrownBy(() -> service.like(USER, PID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_ALREADY_EXISTS);

        verify(likeRepository, never()).saveAndFlush(any());
        verify(postRepository, never()).incrementLikeCount(any());
    }

    @Test
    @DisplayName("좋아요: 없거나 삭제된 글 → COMMUNITY4001, like 저장 없음")
    void like_게시글없음() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.like(USER, PID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(likeRepository);
        verify(postRepository, never()).incrementLikeCount(any());
    }

    @Test
    @DisplayName("좋아요 동시성 backstop: 저장 시 UNIQUE 위반(DataIntegrityViolation) → COMMON4091, 증가 없음")
    void like_동시성_backstop() {
        Post post = post(USER, 5);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, POST_ID)).willReturn(false);
        given(likeRepository.saveAndFlush(any(Like.class)))
                .willThrow(new DataIntegrityViolationException("uk_likes_user_target"));

        assertThatThrownBy(() -> service.like(USER, PID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_ALREADY_EXISTS);

        verify(postRepository, never()).incrementLikeCount(any());
    }

    // ----- unlike -----

    @Test
    @DisplayName("취소 성공: 원자 삭제 영향행=1 → like_count -1, 응답은 재조회한 실제 저장값")
    void unlike_정상() {
        Post post = post(USER, 3);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(likeRepository.deleteByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, POST_ID)).willReturn(1); // 내가 실제로 지움
        // 로드 시점 likeCount=3이라 in-memory 보정은 2지만, 동시 요청으로 DB가 4인 상황을 가정.
        given(postRepository.findLikeCountById(POST_ID)).willReturn(Optional.of(4));

        PostLikeResponse res = service.unlike(USER, PID);

        assertThat(res.getLikeCount()).isEqualTo(4); // in-memory -1(=2)이 아니라 재조회한 실제 저장값
        assertThat(res.isLiked()).isFalse();
        verify(postRepository).decrementLikeCount(POST_ID);
    }

    @Test
    @DisplayName("취소 멱등: 안 누른 글 취소 → 영향행=0 → no-op 200, 감소 호출 없음·like_count 유지")
    void unlike_미좋아요_noop() {
        Post post = post(USER, 3);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(likeRepository.deleteByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, POST_ID)).willReturn(0); // 지울 행 없음

        PostLikeResponse res = service.unlike(USER, PID);

        assertThat(res.getLikeCount()).isEqualTo(3); // 변화 없음
        assertThat(res.isLiked()).isFalse();
        verify(postRepository, never()).decrementLikeCount(any());
    }

    @Test
    @DisplayName("COM-02 회귀: 동시 중복 취소에서 진 쪽(영향행=0)은 decrementLikeCount 호출 안 함(과차감 방지)")
    void unlike_동시중복_진쪽_미차감() {
        Post post = post(USER, 3);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        // 같은 (user, POST, post)를 두 요청이 동시 취소 → 행 락으로 직렬화돼 한쪽만 1, 다른 쪽은 0.
        // 0을 받은 쪽은 감소시키면 안 된다(둘 다 감소하면 단일 좋아요인데 2 차감).
        given(likeRepository.deleteByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, POST_ID)).willReturn(0);

        PostLikeResponse res = service.unlike(USER, PID);

        assertThat(res.getLikeCount()).isEqualTo(3); // 진 쪽은 like_count 변화 없음
        assertThat(res.isLiked()).isFalse();
        verify(postRepository, never()).decrementLikeCount(any());
        verify(postRepository, never()).findLikeCountById(any()); // 감소 안 했으니 재조회도 없음
    }

    @Test
    @DisplayName("취소: 없거나 삭제된 글 → COMMUNITY4001")
    void unlike_게시글없음() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlike(USER, PID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verify(likeRepository, never()).deleteByUserPublicIdAndTargetTypeAndTargetId(any(), any(), any());
        verify(postRepository, never()).decrementLikeCount(any());
    }

    // ----- liked list -----

    @Test
    @DisplayName("관심글 목록 sort 매핑: latest→likedAt 쿼리, popular→likeCount 쿼리")
    void getLikedPosts_sort_매핑() {
        Page<LikedPostProjection> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        given(likeRepository.findLikedPostsOrderByLikedAt(eq(USER), any())).willReturn(empty);
        given(likeRepository.findLikedPostsOrderByLikeCount(eq(USER), any())).willReturn(empty);

        service.getLikedPosts(USER, "latest", 0, 20);
        service.getLikedPosts(USER, "popular", 0, 20);

        verify(likeRepository).findLikedPostsOrderByLikedAt(eq(USER), any());
        verify(likeRepository).findLikedPostsOrderByLikeCount(eq(USER), any());
        verifyNoInteractions(memberClient); // 빈 페이지 → 작성자 조회 없음
    }

    @Test
    @DisplayName("관심글 목록 기본 정렬: sort null/blank → latest(likedAt) 쿼리")
    void getLikedPosts_기본_latest() {
        Page<LikedPostProjection> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        given(likeRepository.findLikedPostsOrderByLikedAt(eq(USER), any())).willReturn(empty);

        service.getLikedPosts(USER, null, 0, 20);

        verify(likeRepository).findLikedPostsOrderByLikedAt(eq(USER), any());
        verify(likeRepository, never()).findLikedPostsOrderByLikeCount(any(), any());
    }

    @Test
    @DisplayName("관심글 목록: 잘못된 sort → COMMON4001, repository·member 호출 없음")
    void getLikedPosts_잘못된_sort() {
        assertThatThrownBy(() -> service.getLikedPosts(USER, "weird", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verifyNoInteractions(likeRepository, memberClient, postRepository);
    }

    @Test
    @DisplayName("관심글 목록 매핑: 행별 Post+likedAt → DTO, 같은 작성자는 member 1회 조회(distinct)")
    void getLikedPosts_정상_매핑() {
        Post postA = post(USER, 1);
        Post postB = post(USER, 2); // 같은 작성자(USER)
        LikedPostProjection r1 = projection(postA, LocalDateTime.of(2026, 5, 27, 9, 30));
        LikedPostProjection r2 = projection(postB, LocalDateTime.of(2026, 5, 26, 9, 30));
        Page<LikedPostProjection> page = new PageImpl<>(List.of(r1, r2), PageRequest.of(0, 20), 2);
        given(likeRepository.findLikedPostsOrderByLikedAt(eq(USER), any())).willReturn(page);
        given(memberClient.getMembers(List.of(USER))).willReturn(Map.of(USER, MINH));

        LikedPostListResponse res = service.getLikedPosts(USER, "latest", 0, 20);

        assertThat(res.getPosts()).hasSize(2);
        assertThat(res.getTotalElements()).isEqualTo(2);
        assertThat(res.getPosts().get(0).getLikedAt()).isEqualTo("2026-05-27T09:30:00Z");
        assertThat(res.getPosts().get(0).getAuthorNickname()).isEqualTo("Minh");
        verify(memberClient, times(1)).getMembers(List.of(USER)); // 같은 작성자 2건 → distinct 1명 배치 1회
    }

    // ----- helpers -----

    /** id·like_count를 지정한 Post 생성(영속화 없이 단위 테스트용). */
    private Post post(String userPublicId, int likeCount) {
        Post post = Post.of(userPublicId, PostCategory.JOB, "ko", "제목", "본문");
        org.springframework.test.util.ReflectionTestUtils.setField(post, "id", POST_ID);
        org.springframework.test.util.ReflectionTestUtils.setField(post, "likeCount", likeCount);
        return post;
    }

    private LikedPostProjection projection(Post post, LocalDateTime likedAt) {
        LikedPostProjection p = mock(LikedPostProjection.class);
        given(p.getPost()).willReturn(post);
        given(p.getLikedAt()).willReturn(likedAt);
        return p;
    }
}
