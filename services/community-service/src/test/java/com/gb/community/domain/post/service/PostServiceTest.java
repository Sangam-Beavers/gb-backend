package com.gb.community.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.like.entity.LikeTargetType;
import com.gb.community.domain.like.repository.LikeRepository;
import com.gb.community.domain.post.dto.request.PostCreateRequest;
import com.gb.community.domain.post.dto.request.PostUpdateRequest;
import com.gb.community.domain.post.dto.response.PostDetailResponse;
import com.gb.community.domain.post.dto.response.PostListResponse;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.post.repository.PostTranslationRepository;
import com.gb.community.domain.post.service.impl.PostServiceImpl;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.event.MilestoneAchieved;
import com.gb.community.global.event.MilestoneType;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
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
    @Mock private LikeRepository likeRepository;
    @Mock private MemberClient memberClient;
    // #161 — 본문/제목 수정 시 번역 캐시 무효화 의존성. updatePost 외 경로는 호출되지 않음.
    @Mock private PostTranslationRepository postTranslationRepository;
    // Phase 3(BE-8): 작성 본문이 마일스톤 내부 이벤트(MilestoneAchieved)를 발행한다 — @Mock이 없으면
    // @InjectMocks 생성자 주입 시 null로 들어가 publishEvent에서 NPE.
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private PostServiceImpl service;

    @BeforeEach
    void injectSelf() {
        // 생성자 주입(@RequiredArgsConstructor)에선 @InjectMocks가 비-final self 필드를 채우지 않아 null.
        // 단위 테스트는 프록시 없이 service 자신을 박아 createPost→createPostTx 등 위임 체인을 그대로 탄다
        // (@Transactional은 단위 테스트에서 no-op — wallet BankAccountServiceTest와 동일 처리).
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000009";
    private static final String PID = "post-uuid-1";

    private static final MemberInfo MINH = new MemberInfo("Minh", true);

    // ----- create -----

    @Test
    @DisplayName("작성 정상: 저장 + 작성자 정보 매핑, category 문자열 매핑")
    void createPost_정상() {
        Post saved = Post.of(USER, PostCategory.JOB, "ko", "제목", "본문");
        given(postRepository.save(any(Post.class))).willReturn(saved);
        given(memberClient.getMember(USER)).willReturn(MINH);

        PostDetailResponse res = service.createPost(USER, createReq("JOB", "제목", "본문"));

        assertThat(res.getCategory()).isEqualTo("JOB");
        assertThat(res.getTitle()).isEqualTo("제목");
        assertThat(res.getContent()).isEqualTo("본문");
        assertThat(res.getAuthorNickname()).isEqualTo("Minh");
        assertThat(res.isAuthorIsVerified()).isTrue();
        assertThat(res.getIsAuthor()).isTrue(); // 작성 응답은 요청자=작성자 — 항상 true
        // is_liked: 방금 생성된 글이라 항상 false — EXISTS 조회 자체를 생략한다(불필요 의존 호출 가드, 명세 §2).
        assertThat(res.getIsLiked()).isFalse();
        verifyNoInteractions(likeRepository);
        verify(postRepository).save(any(Post.class));
        // Phase 3(BE-8): 작성 성공 tx 안에서 COMMUNITY_ACTIVE 내부 이벤트가 발행된다
        // (Kafka 전송은 커밋 후 MilestoneEventPublisher 책임 — 여기선 도메인 발행만 검증).
        verify(eventPublisher).publishEvent(
                new MilestoneAchieved(USER, MilestoneType.COMMUNITY_ACTIVE));
    }

    @Test
    @DisplayName("작성 시 잘못된 category → COMMON4001, save·표시정보 조회 없음(차단 검증 1회만)")
    void createPost_잘못된_카테고리() {
        assertThatThrownBy(() -> service.createPost(USER, createReq("NOPE", "t", "c")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        // 차단 검증(isCommunityBanned)은 진입 시 1회 호출되는 현행 계약 — 표시정보 조회(getMember)는 없어야 한다.
        verify(memberClient).isCommunityBanned(USER);
        verifyNoMoreInteractions(memberClient);
        // Phase 3(BE-8): 작성 실패 시 마일스톤 이벤트도 미발행
        verifyNoInteractions(postRepository, eventPublisher);
    }

    @Test
    @DisplayName("작성 언어 지정(en): resolveLanguage가 그대로 정규화·저장 — Post.language=en")
    void createPost_언어_en_저장() {
        given(postRepository.save(any(Post.class))).willAnswer(inv -> inv.getArgument(0));
        given(memberClient.getMember(USER)).willReturn(MINH);

        service.createPost(USER, createReq("JOB", "en", "title", "content"));

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertThat(captor.getValue().getLanguage()).isEqualTo("en");
    }

    @Test
    @DisplayName("작성 언어 미전송(null): resolveLanguage 기본값 ko로 저장")
    void createPost_언어_미전송_ko_기본값() {
        given(postRepository.save(any(Post.class))).willAnswer(inv -> inv.getArgument(0));
        given(memberClient.getMember(USER)).willReturn(MINH);

        service.createPost(USER, createReq("JOB", null, "title", "content"));

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertThat(captor.getValue().getLanguage()).isEqualTo("ko");
    }

    @Test
    @DisplayName("작성 언어 대소문자·공백 정규화: ' VI ' → vi")
    void createPost_언어_정규화() {
        given(postRepository.save(any(Post.class))).willAnswer(inv -> inv.getArgument(0));
        given(memberClient.getMember(USER)).willReturn(MINH);

        service.createPost(USER, createReq("JOB", "  VI  ", "title", "content"));

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertThat(captor.getValue().getLanguage()).isEqualTo("vi");
    }

    @Test
    @DisplayName("미지원 작성 언어(ja) → COMMUNITY4003, save·표시정보 조회 없음(차단 검증 1회만)")
    void createPost_미지원_언어() {
        assertThatThrownBy(() -> service.createPost(USER, createReq("JOB", "ja", "title", "content")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.UNSUPPORTED_LANGUAGE);

        // 차단 검증(isCommunityBanned)은 진입 시 1회 호출되는 현행 계약 — 표시정보 조회(getMember)는 없어야 한다.
        verify(memberClient).isCommunityBanned(USER);
        verifyNoMoreInteractions(memberClient);
        // Phase 3(BE-8): 작성 실패 시 마일스톤 이벤트도 미발행
        verifyNoInteractions(postRepository, eventPublisher);
    }

    // ----- get -----

    @Test
    @DisplayName("단건 조회 정상: 작성자 정보까지 매핑, 본인 글이면 is_author=true")
    void getPost_정상() {
        Post post = Post.of(USER, PostCategory.VISA, "ko", "비자", "내용");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(memberClient.getMember(USER)).willReturn(MINH);

        PostDetailResponse res = service.getPost(USER, PID);

        assertThat(res.getCategory()).isEqualTo("VISA");
        assertThat(res.getAuthorNickname()).isEqualTo("Minh");
        assertThat(res.getIsAuthor()).isTrue(); // 요청자=작성자
    }

    @Test
    @DisplayName("단건 조회: 타인 글이면 is_author=false (수정·삭제 버튼 비노출 판단)")
    void getPost_타인글_isAuthor_false() {
        Post post = Post.of(OTHER, PostCategory.VISA, "ko", "비자", "내용");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(memberClient.getMember(OTHER)).willReturn(new MemberInfo("Sokha", false));

        PostDetailResponse res = service.getPost(USER, PID);

        assertThat(res.getIsAuthor()).isFalse();
    }

    @Test
    @DisplayName("단건 조회: 좋아요한 글이면 is_liked=true — 좋아요 저장의 409 판정과 동일한 EXISTS 조건으로 계산")
    void getPost_좋아요한_글_isLiked_true() {
        Post post = Post.of(OTHER, PostCategory.VISA, "ko", "비자", "내용");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(memberClient.getMember(OTHER)).willReturn(new MemberInfo("Sokha", false));
        // 요청자(USER) 기준으로 (user, POST, post.id) EXISTS — 좋아요 중복 판정과 같은 조건이어야 한다.
        given(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, post.getId())).willReturn(true);

        PostDetailResponse res = service.getPost(USER, PID);

        assertThat(res.getIsLiked()).isTrue();
        verify(likeRepository).existsByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, post.getId());
    }

    @Test
    @DisplayName("단건 조회: 좋아요 안 한 글이면 is_liked=false (null 아님 — 항상 true/false)")
    void getPost_좋아요_안한_글_isLiked_false() {
        Post post = Post.of(OTHER, PostCategory.VISA, "ko", "비자", "내용");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(memberClient.getMember(OTHER)).willReturn(new MemberInfo("Sokha", false));
        given(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, post.getId())).willReturn(false);

        PostDetailResponse res = service.getPost(USER, PID);

        assertThat(res.getIsLiked()).isFalse();
    }

    @Test
    @DisplayName("단건 조회: 없거나 삭제된 글 → COMMUNITY4001, member 호출 없음")
    void getPost_없음() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPost(USER, PID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(memberClient);
    }

    // ----- update -----

    @Test
    @DisplayName("수정 정상: 보낸 필드(title)만 변경되고 본문은 유지된다 + 번역 캐시 무효화(deleteByPostId 호출)")
    void updatePost_정상() {
        Post post = Post.of(USER, PostCategory.JOB, "ko", "old title", "old content");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(memberClient.getMember(USER)).willReturn(MINH);

        PostDetailResponse res = service.updatePost(USER, PID, updateReq(null, "new title", null));

        assertThat(res.getTitle()).isEqualTo("new title");
        assertThat(res.getContent()).isEqualTo("old content");
        assertThat(res.getIsAuthor()).isTrue(); // 수정은 본인 검증 통과 흐름 — 항상 true
        assertThat(res.getIsLiked()).isFalse(); // EXISTS 미스텁(기본 false) — 좋아요 안 한 본인 글
        assertThat(post.getTitle()).isEqualTo("new title");
        // #161 — title 변경되었으므로 모든 언어 번역 캐시 무효화.
        verify(postTranslationRepository).deleteByPostId(post.getId());
    }

    @Test
    @DisplayName("수정: 본인이 좋아요해 둔 글이면 수정 응답도 is_liked=true (self-like 제한 없음 — 실제 EXISTS 값)")
    void updatePost_본인_좋아요_글_isLiked_true() {
        Post post = Post.of(USER, PostCategory.JOB, "ko", "old title", "old content");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(memberClient.getMember(USER)).willReturn(MINH);
        given(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                USER, LikeTargetType.POST, post.getId())).willReturn(true);

        PostDetailResponse res = service.updatePost(USER, PID, updateReq(null, "new title", null));

        assertThat(res.getIsLiked()).isTrue();
    }

    @Test
    @DisplayName("수정: 타인 글 → COMMON4031, 변경·member 호출 없음")
    void updatePost_타인() {
        Post post = Post.of(OTHER, PostCategory.JOB, "ko", "title", "content");
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
        Post post = Post.of(USER, PostCategory.JOB, "ko", "title", "content");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.updatePost(USER, PID, updateReq("BADCAT", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("수정: 본인 글이지만 all-blank(category·title·content 모두 비움) → COMMON4001, 변경·member 호출 없음")
    void updatePost_all_blank() {
        Post post = Post.of(USER, PostCategory.JOB, "ko", "title", "content");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        // title은 공백("  ") — nullIfBlank로 null 정규화되어 세 값 모두 변경 없음 → 빈 PATCH로 거절돼야 한다.
        assertThatThrownBy(() -> service.updatePost(USER, PID, updateReq(null, "  ", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        assertThat(post.getTitle()).isEqualTo("title"); // 변경 안 됨
        verifyNoInteractions(memberClient);
    }

    // ----- delete -----

    @Test
    @DisplayName("삭제 정상: 본인 글 soft delete (deleted_at 세팅)")
    void deletePost_정상() {
        Post post = Post.of(USER, PostCategory.JOB, "ko", "t", "c");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        service.deletePost(USER, PID);

        assertThat(post.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("삭제: 타인 글 → COMMON4031, 삭제되지 않음")
    void deletePost_타인() {
        Post post = Post.of(OTHER, PostCategory.JOB, "ko", "t", "c");
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

        PostListResponse res = service.getPosts(USER, null, null, "latest", 0, 20);

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

        service.getPosts(USER, null, null, "latest", 0, 20);
        service.getPosts(USER, null, null, "popular", 0, 20);
        service.getPosts(USER, null, null, "accuracy", 0, 20);

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
        Post a = Post.of(USER, PostCategory.JOB, "ko", "t1", "c1");
        Post b = Post.of(USER, PostCategory.VISA, "ko", "t2", "c2");
        given(postRepository.search(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(a, b), PageRequest.of(0, 20), 2));
        given(memberClient.getMembers(List.of(USER))).willReturn(Map.of(USER, MINH));

        PostListResponse res = service.getPosts(USER, null, null, "latest", 0, 20);

        assertThat(res.getPosts()).hasSize(2);
        // 같은 작성자 2건이어도 distinct로 묶어 배치 1회(작성자 1명짜리 리스트)만 호출한다.
        verify(memberClient, times(1)).getMembers(List.of(USER));
    }

    @Test
    @DisplayName("목록 검색: 키워드의 LIKE 메타문자(%, _)를 이스케이프해 repository에 전달")
    void getPosts_키워드_이스케이프() {
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        given(postRepository.search(any(), keywordCaptor.capture(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getPosts(USER, null, "50%_test", "latest", 0, 20);

        // % → |%, _ → |_ (파이프 이스케이프)로 변환돼 넘어가야 한다.
        assertThat(keywordCaptor.getValue()).isEqualTo("50|%|_test");
    }

    @Test
    @DisplayName("목록: 잘못된 sort → COMMON4001, repository·member 호출 없음")
    void getPosts_잘못된_sort() {
        assertThatThrownBy(() -> service.getPosts(USER, null, null, "weird", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verifyNoInteractions(postRepository, memberClient);
    }

    @Test
    @DisplayName("목록 정상: 서로 다른 작성자별로 member 조회 후 항목 매핑, is_author는 항목별 계산(본인 true/타인 false)")
    void getPosts_정상_매핑() {
        Post p1 = Post.of(USER, PostCategory.JOB, "ko", "t1", "c1");
        Post p2 = Post.of(OTHER, PostCategory.VISA, "ko", "t2", "c2");
        Page<Post> page = new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 20), 2);
        given(postRepository.search(any(), any(), any())).willReturn(page);
        given(memberClient.getMembers(List.of(USER, OTHER)))
                .willReturn(Map.of(USER, MINH, OTHER, new MemberInfo("Sokha", false)));

        PostListResponse res = service.getPosts(USER, null, null, "latest", 0, 20);

        assertThat(res.getPosts()).hasSize(2);
        assertThat(res.getTotalElements()).isEqualTo(2);
        assertThat(res.getPosts().get(0).getIsAuthor()).isTrue();  // 본인(USER) 글
        assertThat(res.getPosts().get(1).getIsAuthor()).isFalse(); // 타인(OTHER) 글
        // 서로 다른 작성자 2명을 배치 1회(distinct 작성자 id 리스트)로 조회한다.
        verify(memberClient).getMembers(List.of(USER, OTHER));
    }

    // ----- helpers -----

    private PostCreateRequest createReq(String category, String title, String content) {
        return createReq(category, null, title, content);
    }

    private PostCreateRequest createReq(String category, String language, String title, String content) {
        PostCreateRequest r = new PostCreateRequest();
        ReflectionTestUtils.setField(r, "category", category);
        ReflectionTestUtils.setField(r, "language", language);
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