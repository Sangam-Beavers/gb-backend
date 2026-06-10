package com.gb.community.domain.qna.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.qna.dto.response.QnaListResponse;
import com.gb.community.domain.qna.service.impl.QnaServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QnaServiceImpl} 단위 테스트(Mockito). DB·Spring 컨텍스트 없이 카테고리 파싱·조합·매핑을 본다.
 *
 * <p>정렬(commentCount DESC, id DESC)은 Repository @Query의 ORDER BY 책임이라 단위 테스트에서 검증하지 않는다
 * (Repository 통합 테스트 영역). 여기서는 Mock이 반환한 순서가 응답에 그대로 실리는지만 본다.
 */
@ExtendWith(MockitoExtension.class)
class QnaServiceTest {

    @Mock private PostRepository postRepository;
    @InjectMocks private QnaServiceImpl service;

    private static final String USER = "00000000-0000-0000-0000-000000000001";

    @Test
    @DisplayName("category 미입력 → QUESTION 기본값으로 Repository 호출 + size 5(기본) Pageable")
    void getQnaPosts_카테고리미입력_QUESTION기본() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        given(postRepository.findTopByCategoryOrderByCommentCountDesc(
                eq(PostCategory.QUESTION), pageableCaptor.capture()))
                .willReturn(List.of(
                        post("p1", "E-9 비자로 근무지 변경이 가능한가요?", 7, time(2026, 5, 20, 9, 0, 0)),
                        post("p2", "건강보험 피부양자 등록은 어떻게 하나요?", 4, time(2026, 5, 18, 14, 20, 0))
                ));

        QnaListResponse res = service.getQnaPosts(null, 5);

        assertThat(res.getPosts()).hasSize(2);
        assertThat(res.getPosts().get(0).getPublicId()).isEqualTo("p1");
        assertThat(res.getPosts().get(0).getTitle()).isEqualTo("E-9 비자로 근무지 변경이 가능한가요?");
        assertThat(res.getPosts().get(0).getCommentCount()).isEqualTo(7);
        assertThat(res.getPosts().get(0).getCreatedAt()).isEqualTo("2026-05-20T09:00:00Z");

        // Pageable의 page=0, size=5 검증
        Pageable used = pageableCaptor.getValue();
        assertThat(used.getPageNumber()).isZero();
        assertThat(used.getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("category=JOB 입력 → JOB 카테고리로 Repository 호출")
    void getQnaPosts_카테고리지정_JOB() {
        given(postRepository.findTopByCategoryOrderByCommentCountDesc(
                eq(PostCategory.JOB), any(Pageable.class)))
                .willReturn(List.of(post("p3", "야근수당 계산법", 3, time(2026, 5, 22, 10, 0, 0))));

        QnaListResponse res = service.getQnaPosts("JOB", 5);

        assertThat(res.getPosts()).hasSize(1);
        assertThat(res.getPosts().get(0).getPublicId()).isEqualTo("p3");
        verify(postRepository).findTopByCategoryOrderByCommentCountDesc(
                eq(PostCategory.JOB), any(Pageable.class));
    }

    @Test
    @DisplayName("잘못된 카테고리 → COMMON4001, Repository 호출 없음")
    void getQnaPosts_잘못된카테고리_COMMON4001() {
        assertThatThrownBy(() -> service.getQnaPosts("INVALID_CAT", 5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("빈 결과 → posts 빈 배열, 200 응답")
    void getQnaPosts_빈결과() {
        given(postRepository.findTopByCategoryOrderByCommentCountDesc(
                eq(PostCategory.QUESTION), any(Pageable.class)))
                .willReturn(List.of());

        QnaListResponse res = service.getQnaPosts(null, 5);

        assertThat(res.getPosts()).isEmpty();
    }

    @Test
    @DisplayName("size=20 → 그대로 Pageable에 전달")
    void getQnaPosts_size전달() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        given(postRepository.findTopByCategoryOrderByCommentCountDesc(
                eq(PostCategory.QUESTION), pageableCaptor.capture()))
                .willReturn(List.of());

        service.getQnaPosts(null, 20);

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
    }

    // ----- helpers -----

    /** publicId·title·commentCount·createdAt을 지정한 Post 생성(영속화 없이 단위 테스트용). */
    private Post post(String publicId, String title, int commentCount, LocalDateTime createdAt) {
        Post post = Post.of(USER, PostCategory.QUESTION, "ko", title, "본문");
        ReflectionTestUtils.setField(post, "publicId", publicId);
        ReflectionTestUtils.setField(post, "commentCount", commentCount);
        ReflectionTestUtils.setField(post, "createdAt", createdAt);
        return post;
    }

    private LocalDateTime time(int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s);
    }
}
