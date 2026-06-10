package com.gb.community.domain.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.comment.dto.request.CreateCommentRequest;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.dto.response.CommentResponse;
import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.comment.service.impl.CommentServiceImpl;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.event.MilestoneAchieved;
import com.gb.community.global.event.MilestoneType;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
 * {@link CommentServiceImpl} 단위 테스트(Mockito). DB·Spring 컨텍스트 없이 조합/검증/예외를 본다.
 *
 * <p>검증 포인트: ① 없는 게시글이면 댓글 조회 전에 COMMUNITY4001(게시글 종속), ② 작성자 distinct 1회 조회,
 * ③ 빈 결과면 작성자 조회 없음, ④ Pageable에 최신순(createdAt DESC, id DESC)을 싣는지, ⑤ DTO 매핑.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock private CommentRepository commentRepository;
    @Mock private PostRepository postRepository;
    @Mock private MemberClient memberClient;
    // Phase 3(BE-8): 작성 본문이 마일스톤 내부 이벤트(MilestoneAchieved)를 발행한다 — @Mock이 없으면
    // @InjectMocks 생성자 주입 시 null로 들어가 publishEvent에서 NPE.
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private CommentServiceImpl service;

    @BeforeEach
    void injectSelf() {
        // 생성자 주입(@RequiredArgsConstructor)에선 @InjectMocks가 비-final self 필드를 채우지 않아 null.
        // 단위 테스트는 프록시 없이 service 자신을 박아 createComment→createCommentTx 위임 체인을 그대로 탄다
        // (@Transactional은 단위 테스트에서 no-op — wallet BankAccountServiceTest와 동일 처리).
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";
    private static final String PID = "post-uuid-1";
    private static final String C_PID = "comment-uuid-1";

    private static final MemberInfo MINH = new MemberInfo("Minh", true);
    private static final MemberInfo SOKHA = new MemberInfo("Sokha", false);

    @Test
    @DisplayName("없거나 삭제된 게시글 → COMMUNITY4001, 댓글·작성자 조회 없음")
    void getComments_게시글없음() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getComments(PID, USER, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(commentRepository, memberClient);
    }

    @Test
    @DisplayName("빈 결과: comments 비어있고 작성자(member) 조회 없음, page/size는 페이지 메타 그대로 전달")
    void getComments_빈결과() {
        Post post = post(PID);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        // 비대칭 page=2,size=5로 둬 getNumber()→page, getSize()→size 매핑이 뒤바뀌면 깨지게 한다.
        given(commentRepository.findByPostAndDeletedAtIsNull(any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        CommentListResponse res = service.getComments(PID, USER, 2, 5);

        assertThat(res.getComments()).isEmpty();
        assertThat(res.getTotalElements()).isZero();
        assertThat(res.getPage()).isEqualTo(2);
        assertThat(res.getSize()).isEqualTo(5);
        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("Pageable에 최신순(createdAt DESC, id DESC) tie-break를 싣는다")
    void getComments_정렬_Pageable() {
        Post post = post(PID);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPostAndDeletedAtIsNull(any(), captor.capture()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getComments(PID, USER, 0, 20);

        assertThat(captor.getValue().getSort())
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("매핑 + distinct: 같은 작성자 2건이어도 member 1회 조회, DTO 필드 매핑")
    void getComments_매핑_distinct() {
        Post post = post(PID);
        Comment c1 = comment(post, USER, "첫 댓글", LocalDateTime.of(2026, 5, 26, 4, 15, 30));
        Comment c2 = comment(post, USER, "둘째 댓글", LocalDateTime.of(2026, 5, 26, 5, 0, 0));
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPostAndDeletedAtIsNull(any(), any()))
                .willReturn(new PageImpl<>(List.of(c1, c2), PageRequest.of(0, 20), 2));
        given(memberClient.getMembers(List.of(USER))).willReturn(Map.of(USER, MINH));

        CommentListResponse res = service.getComments(PID, USER, 0, 20);

        assertThat(res.getComments()).hasSize(2);
        assertThat(res.getTotalElements()).isEqualTo(2);

        var first = res.getComments().get(0);
        assertThat(first.getPublicId()).isEqualTo(c1.getPublicId());
        assertThat(first.getPostPublicId()).isEqualTo(PID); // 서비스가 넘긴 게시글 public_id
        assertThat(first.getContent()).isEqualTo("첫 댓글");
        assertThat(first.getAuthorNickname()).isEqualTo("Minh");
        assertThat(first.isAuthorIsVerified()).isTrue();
        assertThat(first.getIsAuthor()).isTrue(); // 요청자(USER)=작성자(USER)
        assertThat(first.getParentCommentPublicId()).isNull(); // 대댓글 미구현 — 항상 null
        assertThat(first.getCreatedAt()).isEqualTo("2026-05-26T04:15:30Z");

        verify(memberClient, times(1)).getMembers(List.of(USER)); // 같은 작성자 2건 → distinct 1명 배치 1회
    }

    @Test
    @DisplayName("매핑: 작성자가 다르면 각각 1회 조회, 인증배지/닉네임/is_author 개별 매핑")
    void getComments_다른작성자_매핑() {
        Post post = post(PID);
        Comment c1 = comment(post, USER, "a", LocalDateTime.of(2026, 5, 26, 4, 0, 0));
        Comment c2 = comment(post, OTHER, "b", LocalDateTime.of(2026, 5, 26, 5, 0, 0));
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPostAndDeletedAtIsNull(any(), any()))
                .willReturn(new PageImpl<>(List.of(c1, c2), PageRequest.of(0, 20), 2));
        given(memberClient.getMembers(List.of(USER, OTHER)))
                .willReturn(Map.of(USER, MINH, OTHER, SOKHA));

        CommentListResponse res = service.getComments(PID, USER, 0, 20);

        assertThat(res.getComments().get(0).getIsAuthor()).isTrue();  // 본인(USER) 댓글
        assertThat(res.getComments().get(1).getAuthorNickname()).isEqualTo("Sokha");
        assertThat(res.getComments().get(1).isAuthorIsVerified()).isFalse();
        assertThat(res.getComments().get(1).getIsAuthor()).isFalse(); // 타인(OTHER) 댓글
        verify(memberClient).getMembers(List.of(USER, OTHER));
    }

    // ==========================================================================
    // createComment(postPublicId, userPublicId, request) — 댓글 작성
    // ==========================================================================

    @Test
    @DisplayName("createComment 정상: Comment INSERT + Post.commentCount +1 + 응답 매핑")
    void createComment_정상() {
        Post post = post(PID);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.save(any(Comment.class))).willAnswer(inv -> {
            Comment c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 100L);
            ReflectionTestUtils.setField(c, "createdAt", LocalDateTime.of(2026, 5, 26, 4, 15, 30));
            return c;
        });
        given(memberClient.getMember(USER)).willReturn(MINH);

        CreateCommentRequest req = createRequest("좋은 정보 감사합니다!");
        CommentResponse resp = service.createComment(PID, USER, req);

        // 응답 매핑 확인
        assertThat(resp.getPostPublicId()).isEqualTo(PID);
        assertThat(resp.getContent()).isEqualTo("좋은 정보 감사합니다!");
        assertThat(resp.getAuthorNickname()).isEqualTo("Minh");
        assertThat(resp.isAuthorIsVerified()).isTrue();
        assertThat(resp.getIsAuthor()).isTrue(); // 작성 응답은 요청자=작성자 — 항상 true
        assertThat(resp.getParentCommentPublicId()).as("대댓글 미지원 — 항상 null").isNull();

        // comment_count 증가는 DB 원자 UPDATE(incrementCommentCount) 호출로 검증(like_count와 동일)
        verify(postRepository).incrementCommentCount(post.getId());

        // Phase 3(BE-8): 작성 성공 tx 안에서 COMMUNITY_DEBUT 내부 이벤트가 발행된다
        // (Kafka 전송은 커밋 후 MilestoneEventPublisher 책임 — 여기선 도메인 발행만 검증).
        verify(eventPublisher).publishEvent(
                new MilestoneAchieved(USER, MilestoneType.COMMUNITY_DEBUT));

        // Comment INSERT 시 parentId는 null (최상위만)
        ArgumentCaptor<Comment> commentCaptor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getParentId()).isNull();
        assertThat(commentCaptor.getValue().getUserPublicId()).isEqualTo(USER);
        assertThat(commentCaptor.getValue().getContent()).isEqualTo("좋은 정보 감사합니다!");
    }

    @Test
    @DisplayName("createComment: 없거나 삭제된 게시글 → COMMUNITY4001, 댓글 INSERT·작성자 조회 없음")
    void createComment_게시글없음_COMMUNITY4001() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createComment(PID, USER, createRequest("내용")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        // Phase 3(BE-8): 작성 실패 시 마일스톤 이벤트도 미발행
        verifyNoInteractions(commentRepository, memberClient, eventPublisher);
    }

    @Test
    @DisplayName("createComment: DB 본문(INSERT·count 증가)이 끝난 뒤에야 MemberClient를 호출하고, 회원 조회 실패가 본문을 막지 않는다")
    void createComment_member조회는_tx본문_이후() {
        // 외부 HTTP(MemberClient)가 쓰기 tx + posts 행 락 안에서 호출되지 않도록 분리한 구조의 회귀 가드:
        // ① 호출 순서 save→incrementCommentCount→getMember, ② getMember가 던져도 save/increment는 이미 수행됨
        //    (실제 커밋·롤백은 단위 범위 밖 — 프록시 tx가 분리돼 있어 본문 커밋은 보존된다).
        Post post = post(PID);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.save(any(Comment.class))).willAnswer(inv -> inv.getArgument(0));
        given(memberClient.getMember(USER)).willThrow(new RuntimeException("member-service down"));

        assertThatThrownBy(() -> service.createComment(PID, USER, createRequest("내용")))
                .isInstanceOf(RuntimeException.class);

        InOrder order = inOrder(commentRepository, postRepository, memberClient);
        order.verify(commentRepository).save(any(Comment.class));
        order.verify(postRepository).incrementCommentCount(post.getId());
        order.verify(memberClient).getMember(USER);
    }

    private CreateCommentRequest createRequest(String content) {
        CreateCommentRequest req = new CreateCommentRequest();
        ReflectionTestUtils.setField(req, "content", content);
        return req;
    }

    // ==========================================================================
    // deleteComment(postPublicId, commentPublicId, userPublicId) — 댓글 삭제
    // ==========================================================================

    @Test
    @DisplayName("deleteComment 정상: 원자 soft delete(affected=1) + post.commentCount -1, MemberClient 호출 없음")
    void deleteComment_정상() {
        Post post = post(PID);
        ReflectionTestUtils.setField(post, "commentCount", 5); // 시드값(원자 UPDATE 호출은 repo verify로 검증)
        Comment c = comment(post, USER, "내용", LocalDateTime.of(2026, 5, 26, 4, 15, 30));
        ReflectionTestUtils.setField(c, "publicId", C_PID);

        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));
        given(commentRepository.softDeleteByPublicId(eq(C_PID), any(LocalDateTime.class))).willReturn(1);

        service.deleteComment(PID, C_PID, USER);

        verify(commentRepository).softDeleteByPublicId(eq(C_PID), any(LocalDateTime.class)); // 원자 조건부 soft delete
        verify(postRepository).decrementCommentCount(post.getId()); // affected==1이라 comment_count -1
        verifyNoInteractions(memberClient); // 삭제는 작성자 정보 조회 불필요
    }

    @Test
    @DisplayName("deleteComment 동시 패자(affected=0): 이미 삭제된 행이면 comment_count 감소하지 않음(과차감 방지)")
    void deleteComment_동시패자_affected0_감소안함() {
        Post post = post(PID);
        ReflectionTestUtils.setField(post, "commentCount", 5);
        Comment c = comment(post, USER, "내용", LocalDateTime.of(2026, 5, 26, 4, 15, 30));
        ReflectionTestUtils.setField(c, "publicId", C_PID);

        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));
        // 동시 삭제: read-time deleted_at IS NULL은 통과했으나 원자 UPDATE 시점엔 다른 트랜잭션이 이미
        // 삭제(deleted_at != NULL) → affected==0(패자). H2는 동시성 미재현 — affected=0 분기만 단언.
        given(commentRepository.softDeleteByPublicId(eq(C_PID), any(LocalDateTime.class))).willReturn(0);

        service.deleteComment(PID, C_PID, USER); // 예외 없이 멱등 종료

        verify(postRepository, never()).decrementCommentCount(any()); // 과차감 방지 — 감소 호출 안 함
    }

    @Test
    @DisplayName("deleteComment: 게시글 없거나 삭제됨 → COMMUNITY4001, 댓글 조회 없음")
    void deleteComment_게시글없음_COMMUNITY4001() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteComment(PID, C_PID, USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(commentRepository, memberClient);
    }

    @Test
    @DisplayName("deleteComment: 댓글 없거나 이미 삭제됨 → COMMUNITY4002")
    void deleteComment_댓글없음_COMMUNITY4002() {
        Post post = post(PID);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteComment(PID, C_PID, USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMENT_NOT_FOUND);

        // 검증 단계라 commentCount는 변하지 않아야 함
        assertThat(post.getCommentCount()).isZero();
    }

    @Test
    @DisplayName("deleteComment: URL 불일치 (댓글이 다른 게시글 소속) → COMMUNITY4002, softDelete 안 됨")
    void deleteComment_URL불일치_COMMUNITY4002() {
        // path의 게시글(id=1)과 댓글의 실제 게시글(id=2)이 다르다 — URL 일관성 위반.
        Post pathPost = post(PID); // id=1
        Post otherPost = post("other-post-pid");
        ReflectionTestUtils.setField(otherPost, "id", 2L); // 다른 id 부여
        Comment c = comment(otherPost, USER, "내용", LocalDateTime.of(2026, 5, 26, 5, 0, 0));
        ReflectionTestUtils.setField(c, "publicId", C_PID);

        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(pathPost));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));

        assertThatThrownBy(() -> service.deleteComment(PID, C_PID, USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMENT_NOT_FOUND);

        verify(commentRepository, never()).softDeleteByPublicId(any(), any()); // URL 검증에서 막혀 삭제 호출 안 됨
        verify(postRepository, never()).decrementCommentCount(any());
    }

    @Test
    @DisplayName("deleteComment: 본인 아님 → COMMON4031, softDelete 안 됨")
    void deleteComment_본인아님_COMMON4031() {
        Post post = post(PID);
        ReflectionTestUtils.setField(post, "commentCount", 3);
        Comment c = comment(post, USER, "내용", LocalDateTime.of(2026, 5, 26, 5, 0, 0));
        ReflectionTestUtils.setField(c, "publicId", C_PID);

        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));

        // OTHER가 USER의 댓글을 삭제 시도
        assertThatThrownBy(() -> service.deleteComment(PID, C_PID, OTHER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(commentRepository, never()).softDeleteByPublicId(any(), any()); // 권한 검증에서 막혀 삭제 호출 안 됨
        verify(postRepository, never()).decrementCommentCount(any());
        assertThat(post.getCommentCount()).as("commentCount도 변하지 않음").isEqualTo(3);
    }

    // ----- helpers -----

    /** public_id·id를 지정한 Post 생성(영속화 없이 단위 테스트용). */
    private Post post(String publicId) {
        Post post = Post.of(USER, PostCategory.JOB, "ko", "글", "내용");
        ReflectionTestUtils.setField(post, "publicId", publicId);
        ReflectionTestUtils.setField(post, "id", 1L);
        return post;
    }

    /** createdAt을 지정한 Comment 생성. publicId는 빌더가 자동 생성, createdAt은 BaseEntity 필드라 reflection으로 박는다. */
    private Comment comment(Post post, String userPublicId, String content, LocalDateTime createdAt) {
        Comment c = Comment.builder()
                .post(post)
                .userPublicId(userPublicId)
                .content(content)
                .build();
        ReflectionTestUtils.setField(c, "createdAt", createdAt);
        return c;
    }
}
