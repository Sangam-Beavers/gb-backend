package com.gb.community.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.post.dto.request.PostCreateRequest;
import com.gb.community.domain.post.dto.request.PostUpdateRequest;
import com.gb.community.domain.post.dto.response.PostDetailResponse;
import com.gb.community.domain.post.dto.response.PostListResponse;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.post.service.impl.PostServiceImpl;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link PostServiceImpl} 단위 테스트(Mockito). DB·Spring 컨텍스트 없이 조합/검증/예외/early-return을 본다.
 *
 * <p>요청 DTO는 setter가 없어 {@link ReflectionTestUtils}로 필드를 채운다(wallet ChargeServiceTest와 동일 패턴).
 */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock private PostRepository postRepository;
    @Mock private MemberClient memberClient;
    @InjectMocks private PostServiceImpl service;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000009";
    private static final String PID = "post-uuid-1";

    private static final MemberInfo MINH = new MemberInfo("Minh", true, "GREEN");

    // ----- create -----

    @Test
    @DisplayName("작성 정상: 저장 + 작성자 정보 매핑, image_urls는 빈 배열, category 문자열 매핑")
    void createPost_정상() {
        Post saved = Post.of(USER, PostCategory.JOB, "제목", "본문");
        given(postRepository.save(any(Post.class))).willReturn(saved);
        given(memberClient.getMember(USER)).willReturn(MINH);

        PostDetailResponse res = service.createPost(USER, createReq("JOB", "제목", "본문"));

        assertThat(res.getCategory()).isEqualTo("JOB");
        assertThat(res.getTitle()).isEqualTo("제목");
        assertThat(res.getContent()).isEqualTo("본문");
        assertThat(res.getImageUrls()).isEmpty();
        assertThat(res.getAuthorNickname()).isEqualTo("Minh");
        assertThat(res.isAuthorIsVerified()).isTrue();
        assertThat(res.getAuthorTemperature()).isEqualTo("GREEN");
        verify(postRepository).save(any(Post.class));
    }

    @Test
    @DisplayName("작성 시 잘못된 category → COMMON4001, save·member 호출 없음")
    void createPost_잘못된_카테고리() {
        assertThatThrownBy(() -> service.createPost(USER, createReq("NOPE", "t", "c")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verifyNoInteractions(postRepository, memberClient);
    }

    // ----- get -----

    @Test
    @DisplayName("단건 조회 정상: 작성자 정보까지 매핑")
    void getPost_정상() {
        Post post = Post.of(USER, PostCategory.VISA, "비자", "내용");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(memberClient.getMember(USER)).willReturn(MINH);

        PostDetailResponse res = service.getPost(PID);

        assertThat(res.getCategory()).isEqualTo("VISA");
        assertThat(res.getAuthorNickname()).isEqualTo("Minh");
    }

    @Test
    @DisplayName("단건 조회: 없거나 삭제된 글 → COMMUNITY4001, member 호출 없음")
    void getPost_없음() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPost(PID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(memberClient);
    }

    // ----- update -----

    @Test
    @DisplayName("수정 정상: 보낸 필드(title)만 변경되고 본문은 유지된다")
    void updatePost_정상() {
        Post post = Post.of(USER, PostCategory.JOB, "old title", "old content");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(memberClient.getMember(USER)).willReturn(MINH);

        PostDetailResponse res = service.updatePost(USER, PID, updateReq(null, "new title", null));

        assertThat(res.getTitle()).isEqualTo("new title");
        assertThat(res.getContent()).isEqualTo("old content");
        assertThat(post.getTitle()).isEqualTo("new title");
    }

    @Test
    @DisplayName("수정: 타인 글 → COMMON4031, 변경·member 호출 없음")
    void updatePost_타인() {
        Post post = Post.of(OTHER, PostCategory.JOB, "title", "content");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.updatePost(USER, PID, updateReq(null, "x", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        assertThat(post.getTitle()).isEqualTo("title"); // 변경 안 됨
        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("수정: 없는 글 → COMMUNITY4001, member 호출 없음")
    void updatePost_없음() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePost(USER, PID, updateReq(null, "x", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("수정: 본인 글이지만 잘못된 category → COMMON4001, member 호출 없음")
    void updatePost_잘못된_카테고리() {
        Post post = Post.of(USER, PostCategory.JOB, "title", "content");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.updatePost(USER, PID, updateReq("BADCAT", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verifyNoInteractions(memberClient);
    }

    // ----- delete -----

    @Test
    @DisplayName("삭제 정상: 본인 글 soft delete (deleted_at 세팅)")
    void deletePost_정상() {
        Post post = Post.of(USER, PostCategory.JOB, "t", "c");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        service.deletePost(USER, PID);

        assertThat(post.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("삭제: 타인 글 → COMMON4031, 삭제되지 않음")
    void deletePost_타인() {
        Post post = Post.of(OTHER, PostCategory.JOB, "t", "c");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.deletePost(USER, PID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        assertThat(post.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("삭제: 없는 글 → COMMUNITY4001, member 호출 없음")
    void deletePost_없음() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deletePost(USER, PID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(memberClient);
    }

    // ----- list -----

    @Test
    @DisplayName("목록 빈 결과: posts 비어있고 member 호출 없음")
    void getPosts_빈결과() {
        Page<Post> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        given(postRepository.search(any(), any(), any())).willReturn(empty);

        PostListResponse res = service.getPosts(null, null, "latest", 0, 20);

        assertThat(res.getPosts()).isEmpty();
        assertThat(res.getTotalElements()).isZero();
        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("목록: sort 매핑 — latest=createdAt desc, popular=likeCount→createdAt desc, accuracy=latest와 동치")
    void getPosts_sort_매핑() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        given(postRepository.search(any(), any(), captor.capture()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getPosts(null, null, "latest", 0, 20);
        service.getPosts(null, null, "popular", 0, 20);
        service.getPosts(null, null, "accuracy", 0, 20);

        List<Pageable> captured = captor.getAllValues();
        assertThat(captured.get(0).getSort()) // latest
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        assertThat(captured.get(1).getSort()) // popular: likeCount 우선, createdAt tie-break
                .containsExactly(Sort.Order.desc("likeCount"), Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id"));
        assertThat(captured.get(2).getSort()) // accuracy == latest
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("목록: 같은 작성자 글이 여러 개여도 member 조회는 작성자당 1회(distinct)")
    void getPosts_같은작성자_중복조회_없음() {
        Post a = Post.of(USER, PostCategory.JOB, "t1", "c1");
        Post b = Post.of(USER, PostCategory.VISA, "t2", "c2");
        given(postRepository.search(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(a, b), PageRequest.of(0, 20), 2));
        given(memberClient.getMember(USER)).willReturn(MINH);

        PostListResponse res = service.getPosts(null, null, "latest", 0, 20);

        assertThat(res.getPosts()).hasSize(2);
        verify(memberClient, times(1)).getMember(USER);
    }

    @Test
    @DisplayName("목록: 잘못된 sort → COMMON4001, repository·member 호출 없음")
    void getPosts_잘못된_sort() {
        assertThatThrownBy(() -> service.getPosts(null, null, "weird", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verifyNoInteractions(postRepository, memberClient);
    }

    @Test
    @DisplayName("목록 정상: 서로 다른 작성자별로 member 조회 후 항목 매핑")
    void getPosts_정상_매핑() {
        Post p1 = Post.of(USER, PostCategory.JOB, "t1", "c1");
        Post p2 = Post.of(OTHER, PostCategory.VISA, "t2", "c2");
        Page<Post> page = new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 20), 2);
        given(postRepository.search(any(), any(), any())).willReturn(page);
        given(memberClient.getMember(USER)).willReturn(MINH);
        given(memberClient.getMember(OTHER)).willReturn(new MemberInfo("Sokha", false, "YELLOW"));

        PostListResponse res = service.getPosts(null, null, "latest", 0, 20);

        assertThat(res.getPosts()).hasSize(2);
        assertThat(res.getTotalElements()).isEqualTo(2);
        verify(memberClient).getMember(USER);
        verify(memberClient).getMember(OTHER);
    }

    // ----- helpers -----

    private PostCreateRequest createReq(String category, String title, String content) {
        PostCreateRequest r = new PostCreateRequest();
        ReflectionTestUtils.setField(r, "category", category);
        ReflectionTestUtils.setField(r, "title", title);
        ReflectionTestUtils.setField(r, "content", content);
        return r;
    }

    private PostUpdateRequest updateReq(String category, String title, String content) {
        PostUpdateRequest r = new PostUpdateRequest();
        ReflectionTestUtils.setField(r, "category", category);
        ReflectionTestUtils.setField(r, "title", title);
        ReflectionTestUtils.setField(r, "content", content);
        return r;
    }
}