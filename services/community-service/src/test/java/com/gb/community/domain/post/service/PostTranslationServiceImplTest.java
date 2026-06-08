package com.gb.community.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.community.domain.post.dto.response.PostTranslationResponse;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.entity.PostTranslation;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.post.repository.PostTranslationRepository;
import com.gb.community.domain.post.service.impl.PostTranslationServiceImpl;
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

/**
 * {@link PostTranslationServiceImpl} 단위 테스트 — Mockito로 캐시/Bedrock 호출 분기 검증.
 *
 * <p>경로: 같은 언어 skip / 캐시 hit / 캐시 miss → Lambda 호출 후 save / 미지원 언어 / 본문 초과 / 없는 게시글.
 */
@ExtendWith(MockitoExtension.class)
class PostTranslationServiceImplTest {

    @Mock private PostRepository postRepository;
    @Mock private PostTranslationRepository postTranslationRepository;
    @Mock private TranslationClient translationClient;
    @InjectMocks private PostTranslationServiceImpl service;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String PID = "post-uuid-1";

    private Post postKo(String content) {
        return Post.of(USER, PostCategory.JOB, "최저임금", content);
    }

    @Test
    @DisplayName("같은 언어 요청(ko↔ko): Bedrock 호출도 캐시 조회도 없이 원문 그대로 반환")
    void 같은_언어_원문_반환() {
        Post post = postKo("시급이 낮아요");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        PostTranslationResponse res = service.getOrTranslate(PID, "ko");

        assertThat(res.getTranslatedTitle()).isEqualTo("최저임금");
        assertThat(res.getTranslatedContent()).isEqualTo("시급이 낮아요");
        assertThat(res.getTranslatedLanguage()).isEqualTo("ko");
        // 같은 언어 경로는 캐시 조회·Bedrock 호출·캐시 INSERT가 모두 일어나지 않는다.
        verifyNoInteractions(postTranslationRepository, translationClient);
    }

    @Test
    @DisplayName("캐시 hit: post_translations에 있는 행이면 즉시 반환 — Bedrock 호출 없음")
    void 캐시_hit() {
        Post post = postKo("시급이 낮아요");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        PostTranslation cached = PostTranslation.of(post, "vi", "[VI] 최저임금", "[VI] 시급이 낮아요");
        given(postTranslationRepository.findByPostIdAndLanguage(post.getId(), "vi"))
                .willReturn(Optional.of(cached));

        PostTranslationResponse res = service.getOrTranslate(PID, "vi");

        assertThat(res.getTranslatedTitle()).isEqualTo("[VI] 최저임금");
        assertThat(res.getTranslatedContent()).isEqualTo("[VI] 시급이 낮아요");
        assertThat(res.getTranslatedLanguage()).isEqualTo("vi");
        verifyNoInteractions(translationClient);
    }

    @Test
    @DisplayName("캐시 miss: TranslationClient 호출 후 save — translatedLanguage는 요청 target_lang")
    void 캐시_miss_Lambda_호출() {
        Post post = postKo("시급이 낮아요");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(postTranslationRepository.findByPostIdAndLanguage(post.getId(), "vi"))
                .willReturn(Optional.empty());
        TranslationResult bedrockResult = new TranslationResult(
                "[VI] 최저임금", "[VI] 시급이 낮아요", "vi", "mock", 0, 0);
        given(translationClient.translate(
                eq("post"), eq(post.getPublicId()), eq("최저임금"), eq("시급이 낮아요"), eq("ko"), eq("vi")))
                .willReturn(bedrockResult);
        // save가 받은 인자를 그대로 반환하도록 — 응답 매핑 확인.
        given(postTranslationRepository.save(any(PostTranslation.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PostTranslationResponse res = service.getOrTranslate(PID, "vi");

        assertThat(res.getTranslatedTitle()).isEqualTo("[VI] 최저임금");
        assertThat(res.getTranslatedContent()).isEqualTo("[VI] 시급이 낮아요");
        assertThat(res.getTranslatedLanguage()).isEqualTo("vi");
        verify(postTranslationRepository).save(any(PostTranslation.class));
    }

    @Test
    @DisplayName("미지원 언어 → COMMUNITY4003, Bedrock·캐시 호출 없음")
    void 미지원_언어() {
        Post post = postKo("시급이 낮아요");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.getOrTranslate(PID, "ja"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.UNSUPPORTED_LANGUAGE);

        verifyNoInteractions(postTranslationRepository, translationClient);
    }

    @Test
    @DisplayName("화이트리스트 대소문자 정규화: VI도 vi로 통과")
    void 대소문자_정규화_통과() {
        Post post = postKo("시급이 낮아요");
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));
        given(postTranslationRepository.findByPostIdAndLanguage(post.getId(), "vi"))
                .willReturn(Optional.of(PostTranslation.of(post, "vi", "t", "c")));

        PostTranslationResponse res = service.getOrTranslate(PID, "VI");

        assertThat(res.getTranslatedLanguage()).isEqualTo("vi");
    }

    @Test
    @DisplayName("본문 5000자 초과 → COMMUNITY4004, Bedrock·캐시 호출 없음")
    void 본문_초과() {
        Post post = postKo("가".repeat(5001));
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.getOrTranslate(PID, "vi"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.CONTENT_TOO_LONG);

        verifyNoInteractions(postTranslationRepository, translationClient);
    }

    @Test
    @DisplayName("없는 게시글 → COMMUNITY4001, 후속 호출 없음")
    void 없는_게시글() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull(PID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrTranslate(PID, "vi"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verifyNoInteractions(postTranslationRepository, translationClient);
    }
}
