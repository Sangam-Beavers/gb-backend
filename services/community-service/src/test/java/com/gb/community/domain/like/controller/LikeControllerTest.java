package com.gb.community.domain.like.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.like.dto.response.LikedPostListResponse;
import com.gb.community.domain.like.dto.response.LikedPostSummaryResponse;
import com.gb.community.domain.like.dto.response.PostLikeResponse;
import com.gb.community.domain.like.service.LikeService;
import com.gb.community.domain.post.controller.PostController;
import com.gb.community.domain.post.service.PostService;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link LikeController} HTTP wiring 검증 — URL/메서드, @ResponseStatus(201), ApiResponse 래핑,
 * snake_case 직렬화(liked_at), BusinessException → ErrorResponse 변환.
 *
 * <p><b>라우팅 공존 검증</b>이 핵심: {@link PostController}를 함께 로드해 {@code GET /posts/liked}가
 * {@code GET /posts/{id}}(PostController)로 새지 않고 LikeController로 매핑되는지 확인한다(리터럴 우선).
 * {@code GlobalExceptionHandler}·{@code SecurityConfig}는 운영과 동일 경로로 명시 import한다.
 */
@WebMvcTest({LikeController.class, PostController.class})
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.community.global.config.SecurityConfig.class
})
class LikeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LikeService likeService;

    @MockitoBean
    private PostService postService; // PostController 의존성 충족 + 라우팅 격리 검증용

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String PID = "a1b2c3d4-0000-0000-0000-000000000001";

    // ----- GET /posts/liked (라우팅 공존) -----

    @Test
    @DisplayName("GET /posts/liked → LikeController로 매핑(PostController.getPost로 안 샘), 200")
    void getLikedPosts_라우팅_200() throws Exception {
        given(likeService.getLikedPosts(eq(USER), any(), eq(0), eq(20)))
                .willReturn(LikedPostListResponse.of(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/community/posts/liked")
                        .header("X-User-Public-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.posts").isArray())
                .andExpect(jsonPath("$.data.total_elements").value(0));

        verify(likeService).getLikedPosts(eq(USER), any(), eq(0), eq(20));
        verifyNoInteractions(postService); // /liked가 단건 조회로 새지 않았음을 보장
    }

    @Test
    @DisplayName("GET /posts/liked 200: liked_at 등 snake_case 직렬화")
    void getLikedPosts_snake_case() throws Exception {
        LikedPostSummaryResponse item = LikedPostSummaryResponse.builder()
                .publicId(PID)
                .category("JOB")
                .title("제목")
                .contentPreview("미리보기")
                .authorNickname("Minh")
                .authorTemperature("GREEN")
                .likeCount(3)
                .commentCount(2)
                .createdAt("2026-05-26T04:15:30Z")
                .likedAt("2026-05-27T09:30:00Z")
                .build();
        given(likeService.getLikedPosts(eq(USER), any(), eq(0), eq(20)))
                .willReturn(LikedPostListResponse.of(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/community/posts/liked")
                        .header("X-User-Public-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_elements").value(1))
                .andExpect(jsonPath("$.data.posts[0].public_id").value(PID))
                .andExpect(jsonPath("$.data.posts[0].author_temperature").value("GREEN"))
                .andExpect(jsonPath("$.data.posts[0].like_count").value(3))
                .andExpect(jsonPath("$.data.posts[0].created_at").value("2026-05-26T04:15:30Z"))
                .andExpect(jsonPath("$.data.posts[0].liked_at").value("2026-05-27T09:30:00Z"));
    }

    @Test
    @DisplayName("GET /posts/liked 400: 헤더 누락 → COMMON4001, service 미호출")
    void getLikedPosts_헤더_누락() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/liked"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(likeService);
    }

    // ----- POST /posts/{id}/likes -----

    @Test
    @DisplayName("POST /{id}/likes 201: 좋아요 성공 → 201 + snake_case(post_public_id/like_count/liked)")
    void like_정상_201() throws Exception {
        given(likeService.like(USER, PID)).willReturn(PostLikeResponse.of(PID, 4, true));

        mockMvc.perform(post("/api/v1/community/posts/{id}/likes", PID)
                        .header("X-User-Public-Id", USER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.post_public_id").value(PID))
                .andExpect(jsonPath("$.data.like_count").value(4))
                .andExpect(jsonPath("$.data.liked").value(true));

        verify(likeService).like(USER, PID);
    }

    @Test
    @DisplayName("POST /{id}/likes 409: 중복(service가 COMMON4091) → 409 + code")
    void like_중복_409() throws Exception {
        given(likeService.like(USER, PID))
                .willThrow(new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS));

        mockMvc.perform(post("/api/v1/community/posts/{id}/likes", PID)
                        .header("X-User-Public-Id", USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON4091"));
    }

    @Test
    @DisplayName("POST /{id}/likes 404: 없는 글(service가 COMMUNITY4001) → 404 + code")
    void like_게시글없음_404() throws Exception {
        given(likeService.like(USER, PID))
                .willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        mockMvc.perform(post("/api/v1/community/posts/{id}/likes", PID)
                        .header("X-User-Public-Id", USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    @Test
    @DisplayName("POST /{id}/likes 400: 헤더 누락 → COMMON4001, service 미호출")
    void like_헤더_누락() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts/{id}/likes", PID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(likeService);
    }

    // ----- DELETE /posts/{id}/likes -----

    @Test
    @DisplayName("DELETE /{id}/likes 200: 취소 성공 → 200 + liked=false")
    void unlike_정상_200() throws Exception {
        given(likeService.unlike(USER, PID)).willReturn(PostLikeResponse.of(PID, 2, false));

        mockMvc.perform(delete("/api/v1/community/posts/{id}/likes", PID)
                        .header("X-User-Public-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.like_count").value(2))
                .andExpect(jsonPath("$.data.liked").value(false));

        verify(likeService).unlike(USER, PID);
    }
}
