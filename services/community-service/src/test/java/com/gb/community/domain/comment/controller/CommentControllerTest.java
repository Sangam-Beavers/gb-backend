package com.gb.community.domain.comment.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gb.common.exception.BusinessException;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.dto.response.CommentResponse;
import com.gb.community.domain.comment.service.CommentService;
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
 * {@link CommentController} HTTP wiring 검증 — URL/메서드, @Validated, @RequestHeader, ApiResponse 래핑,
 * snake_case 직렬화, BusinessException → ErrorResponse 변환.
 *
 * <p>{@code GlobalExceptionHandler}(common-exception)와 {@code SecurityConfig}(슬라이스 필터에서 제외)는
 * 명시 {@code @Import}로 가져와 운영과 동일 경로(permitAll + 공용 예외 처리기)로 검증한다(PostControllerTest와 동일).
 */
@WebMvcTest(CommentController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.community.global.config.SecurityConfig.class
})
class CommentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService commentService;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String PID = "a1b2c3d4-0000-0000-0000-000000000001";
    private static final String CID = "c1d2e3f4-0000-0000-0000-000000000001";

    @Test
    @DisplayName("GET 200: 댓글 목록 → comments 배열 + 페이지 메타(snake_case), parent_comment_public_id는 null")
    void getComments_정상() throws Exception {
        given(commentService.getComments(eq(PID), eq(0), eq(20)))
                .willReturn(CommentListResponse.of(List.of(stubComment()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID)
                        .header("X-User-Public-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.comments").isArray())
                .andExpect(jsonPath("$.data.comments[0].public_id").value(CID))
                .andExpect(jsonPath("$.data.comments[0].post_public_id").value(PID))
                .andExpect(jsonPath("$.data.comments[0].parent_comment_public_id").value(nullValue()))
                .andExpect(jsonPath("$.data.comments[0].content").value("댓글 내용"))
                .andExpect(jsonPath("$.data.comments[0].author_nickname").value("Minh"))
                .andExpect(jsonPath("$.data.comments[0].author_is_verified").value(true))
                .andExpect(jsonPath("$.data.comments[0].created_at").value("2026-05-26T04:15:30Z"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total_elements").value(1))
                .andExpect(jsonPath("$.data.total_pages").value(1));

        verify(commentService).getComments(PID, 0, 20);
    }

    @Test
    @DisplayName("GET 404: service가 COMMUNITY4001 던지면 → 404 + code")
    void getComments_게시글없음_404() throws Exception {
        given(commentService.getComments(eq(PID), any(Integer.class), any(Integer.class)))
                .willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID)
                        .header("X-User-Public-Id", USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    @Test
    @DisplayName("GET 400: size 상한(100) 초과 → COMMON4001(@Max 위반), service 미호출")
    void getComments_size_초과() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID)
                        .header("X-User-Public-Id", USER)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("GET 400: X-User-Public-Id 헤더 누락 → COMMON4001, service 미호출")
    void getComments_헤더_누락() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentService);
    }

    // ----- helpers -----

    private CommentResponse stubComment() {
        return CommentResponse.builder()
                .publicId(CID)
                .postPublicId(PID)
                .parentCommentPublicId(null)
                .content("댓글 내용")
                .authorNickname("Minh")
                .authorIsVerified(true)
                .createdAt("2026-05-26T04:15:30Z")
                .build();
    }
}
