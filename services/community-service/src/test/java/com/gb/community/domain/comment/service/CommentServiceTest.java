package com.gb.community.domain.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.comment.service.impl.CommentServiceImpl;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.time.LocalDateTime;
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
 * {@link CommentServiceImpl} 단위 테스트(Mockito). DB·Spring 컨텍스트 없이 조합/검증/예외를 본다.
 *
 * <p>검증 포인트: ① 없는 게시글이면 댓글 조회 전에 COMMUNITY4001(게시글 종속), ② 작성자 distinct 1회 조회,
 * ③ 빈 결과면 작성자 조회 없음, ④ Pageable에 작성순(createdAt ASC, id ASC)을 싣는지, ⑤ DTO 매핑.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock private CommentRepository commentRepository;
    @Mock private PostRepository postRepository;
    @Mock private MemberClient memberClient;
    @InjectMocks private CommentServiceImpl service;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";
    private static final String PID = "post-uuid-1";

    private static final MemberInfo MINH = new MemberInfo("Minh", true, "GREEN");
    private static final MemberInfo SOKHA = new MemberInfo("Sokha", false, "YELLOW");

    @Test
    @DisplayName("없거나 삭제된 게시글 → COMMUNITY4001, 댓글·작성자 조회 없음")
    void getComments_게시글없음() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getComments(PID, 0, 20))
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

        CommentListResponse res = service.getComments(PID, 2, 5);

        assertThat(res.getComments()).isEmpty();
        assertThat(res.getTotalElements()).isZero();
        assertThat(res.getPage()).isEqualTo(2);
        assertThat(res.getSize()).isEqualTo(5);
        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("Pageable에 작성순(createdAt ASC, id ASC) tie-break를 싣는다")
    void getComments_정렬_Pageable() {
        Post post = post(PID);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPostAndDeletedAtIsNull(any(), captor.capture()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getComments(PID, 0, 20);

        assertThat(captor.getValue().getSort())
                .containsExactly(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
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
        given(memberClient.getMember(USER)).willReturn(MINH);

        CommentListResponse res = service.getComments(PID, 0, 20);

        assertThat(res.getComments()).hasSize(2);
        assertThat(res.getTotalElements()).isEqualTo(2);

        var first = res.getComments().get(0);
        assertThat(first.getPublicId()).isEqualTo(c1.getPublicId());
        assertThat(first.getPostPublicId()).isEqualTo(PID); // 서비스가 넘긴 게시글 public_id
        assertThat(first.getContent()).isEqualTo("첫 댓글");
        assertThat(first.getAuthorNickname()).isEqualTo("Minh");
        assertThat(first.isAuthorIsVerified()).isTrue();
        assertThat(first.getParentCommentPublicId()).isNull(); // 대댓글 미구현 — 항상 null
        assertThat(first.getCreatedAt()).isEqualTo("2026-05-26T04:15:30Z");

        verify(memberClient, times(1)).getMember(USER); // 같은 작성자 2건이어도 1회
    }

    @Test
    @DisplayName("매핑: 작성자가 다르면 각각 1회 조회, 인증배지/닉네임 개별 매핑")
    void getComments_다른작성자_매핑() {
        Post post = post(PID);
        Comment c1 = comment(post, USER, "a", LocalDateTime.of(2026, 5, 26, 4, 0, 0));
        Comment c2 = comment(post, OTHER, "b", LocalDateTime.of(2026, 5, 26, 5, 0, 0));
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(commentRepository.findByPostAndDeletedAtIsNull(any(), any()))
                .willReturn(new PageImpl<>(List.of(c1, c2), PageRequest.of(0, 20), 2));
        given(memberClient.getMember(USER)).willReturn(MINH);
        given(memberClient.getMember(OTHER)).willReturn(SOKHA);

        CommentListResponse res = service.getComments(PID, 0, 20);

        assertThat(res.getComments().get(1).getAuthorNickname()).isEqualTo("Sokha");
        assertThat(res.getComments().get(1).isAuthorIsVerified()).isFalse();
        verify(memberClient).getMember(USER);
        verify(memberClient).getMember(OTHER);
    }

    // ----- helpers -----

    /** public_id·id를 지정한 Post 생성(영속화 없이 단위 테스트용). */
    private Post post(String publicId) {
        Post post = Post.of(USER, PostCategory.JOB, "글", "내용");
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
