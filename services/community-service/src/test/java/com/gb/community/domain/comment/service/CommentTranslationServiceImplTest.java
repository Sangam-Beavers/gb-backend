package com.gb.community.domain.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.community.domain.comment.dto.response.CommentTranslationResponse;
import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.entity.CommentTranslation;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.comment.repository.CommentTranslationRepository;
import com.gb.community.domain.comment.service.impl.CommentTranslationServiceImpl;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.global.client.TranslationClient;
import com.gb.community.global.client.TranslationResult;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link CommentTranslationServiceImpl} 단위 테스트.
 * Post 번역과 거의 동일하되, 댓글에는 language 컬럼이 없어 부모 게시글 언어로 같은-언어 판정한다는 점,
 * 그리고 URL postId-댓글 post 불일치 검증이 추가된다는 점이 다르다.
 */
@ExtendWith(MockitoExtension.class)
class CommentTranslationServiceImplTest {

    @Mock private PostRepository postRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private CommentTranslationRepository commentTranslationRepository;
    @Mock private TranslationClient translationClient;
    @InjectMocks private CommentTranslationServiceImpl service;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String P_PID = "post-uuid-1";
    private static final String C_PID = "comment-uuid-1";

    private Post post(Long id) {
        Post p = Post.of(USER, PostCategory.JOB, "title", "post content");
        // post.id는 ManyToOne 비교에 쓰여서 명시 세팅. (PostTranslationServiceImplTest는 Mock의 id 자동 null이라
        // 이 테스트만 명시 — equals 비교가 들어가서)
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private Comment commentOf(Post p, String content) {
        Comment c = Comment.builder().post(p).userPublicId(USER).parentId(null).content(content).build();
        return c;
    }

    @Test
    @DisplayName("같은 언어(부모 게시글 language=ko, target=ko): 원문 그대로 — Bedrock·캐시 호출 없음")
    void 같은_언어_원문_반환() {
        Post p = post(1L);
        Comment c = commentOf(p, "댓글 본문");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(P_PID)).willReturn(Optional.of(p));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));

        CommentTranslationResponse res = service.getOrTranslate(P_PID, C_PID, "ko");

        assertThat(res.getTranslatedContent()).isEqualTo("댓글 본문");
        assertThat(res.getTranslatedLanguage()).isEqualTo("ko");
        verifyNoInteractions(commentTranslationRepository, translationClient);
    }

    @Test
    @DisplayName("캐시 hit: 즉시 반환")
    void 캐시_hit() {
        Post p = post(1L);
        Comment c = commentOf(p, "댓글 본문");
        ReflectionTestUtils.setField(c, "id", 10L);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(P_PID)).willReturn(Optional.of(p));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));
        given(commentTranslationRepository.findByCommentIdAndLanguage(10L, "vi"))
                .willReturn(Optional.of(CommentTranslation.of(c, "vi", "[VI] 댓글 본문")));

        CommentTranslationResponse res = service.getOrTranslate(P_PID, C_PID, "vi");

        assertThat(res.getTranslatedContent()).isEqualTo("[VI] 댓글 본문");
        verifyNoInteractions(translationClient);
    }

    @Test
    @DisplayName("캐시 miss: Bedrock 호출 — title은 null로 넘어가야 한다(댓글 계약)")
    void 캐시_miss_title_null() {
        Post p = post(1L);
        Comment c = commentOf(p, "댓글 본문");
        ReflectionTestUtils.setField(c, "id", 10L);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(P_PID)).willReturn(Optional.of(p));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));
        given(commentTranslationRepository.findByCommentIdAndLanguage(10L, "vi"))
                .willReturn(Optional.empty());
        given(translationClient.translate(
                eq("comment"), eq(c.getPublicId()), isNull(), eq("댓글 본문"), eq("ko"), eq("vi")))
                .willReturn(new TranslationResult(null, "[VI] 댓글 본문", "vi", "mock", 0, 0));
        given(commentTranslationRepository.save(any(CommentTranslation.class)))
                .willAnswer(inv -> inv.getArgument(0));

        CommentTranslationResponse res = service.getOrTranslate(P_PID, C_PID, "vi");

        assertThat(res.getTranslatedContent()).isEqualTo("[VI] 댓글 본문");
        verify(commentTranslationRepository).save(any(CommentTranslation.class));
    }

    @Test
    @DisplayName("URL postId와 댓글의 실제 post 불일치 → COMMUNITY4002 (권한 문제 아닌 자원 불일치 통합)")
    void postId_불일치() {
        Post urlPost = post(1L);
        Post realPost = post(2L); // 다른 게시글의 댓글
        Comment c = commentOf(realPost, "댓글");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(P_PID)).willReturn(Optional.of(urlPost));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));

        assertThatThrownBy(() -> service.getOrTranslate(P_PID, C_PID, "vi"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMENT_NOT_FOUND);

        verifyNoInteractions(commentTranslationRepository, translationClient);
    }

    @Test
    @DisplayName("미지원 언어 → COMMUNITY4003")
    void 미지원_언어() {
        Post p = post(1L);
        Comment c = commentOf(p, "댓글");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(P_PID)).willReturn(Optional.of(p));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));

        assertThatThrownBy(() -> service.getOrTranslate(P_PID, C_PID, "ja"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.UNSUPPORTED_LANGUAGE);

        verifyNoInteractions(commentTranslationRepository, translationClient);
    }

    @Test
    @DisplayName("본문 5000자 초과 → COMMUNITY4004")
    void 본문_초과() {
        Post p = post(1L);
        Comment c = commentOf(p, "가".repeat(5001));
        given(postRepository.findByPublicIdAndDeletedAtIsNull(P_PID)).willReturn(Optional.of(p));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.of(c));

        assertThatThrownBy(() -> service.getOrTranslate(P_PID, C_PID, "vi"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.CONTENT_TOO_LONG);

        verifyNoInteractions(commentTranslationRepository, translationClient);
    }

    @Test
    @DisplayName("없는 게시글 → COMMUNITY4001 (댓글 검증 전에 차단)")
    void 없는_게시글() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(P_PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrTranslate(P_PID, C_PID, "vi"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(commentRepository, commentTranslationRepository, translationClient);
    }

    @Test
    @DisplayName("없는 댓글 → COMMUNITY4002 (게시글은 있음)")
    void 없는_댓글() {
        Post p = post(1L);
        given(postRepository.findByPublicIdAndDeletedAtIsNull(P_PID)).willReturn(Optional.of(p));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull(C_PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrTranslate(P_PID, C_PID, "vi"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMENT_NOT_FOUND);

        verifyNoInteractions(commentTranslationRepository, translationClient);
    }
}
