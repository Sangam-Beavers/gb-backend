package com.gb.community.domain.comment.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gb.common.exception.BusinessException;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.community.domain.comment.dto.response.CommentTranslationResponse;
import com.gb.community.domain.comment.service.CommentService;
import com.gb.community.domain.comment.service.CommentTranslationService;
import com.gb.community.global.config.WebConfig;
import com.gb.community.global.exception.code.CommunityErrorCode;
import com.gb.community.global.security.CurrentUserPublicIdArgumentResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link CommentController}의 댓글 번역 엔드포인트 HTTP wiring 검증.
 * {@code GET /api/v1/community/posts/{postId}/comments/{commentId}/translation?language=...}
 */
@WebMvcTest(CommentController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.community.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class CommentTranslationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService commentService;

    @MockitoBean
    private CommentTranslationService commentTranslationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String P_PID = "a1b2c3d4-0000-0000-0000-000000000001";
    private static final String C_PID = "c1d2e3f4-0000-0000-0000-000000000001";

    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER));
    }

    @Test
    @DisplayName("200: 정상 번역 — translated_content/translated_language 직렬화")
    void 정상_200() throws Exception {
        given(commentTranslationService.getOrTranslate(eq(P_PID), eq(C_PID), eq("vi")))
                .willReturn(CommentTranslationResponse.builder()
                        .translatedContent("[VI] 댓글 본문")
                        .translatedLanguage("vi")
                        .build());

        mockMvc.perform(get("/api/v1/community/posts/{postId}/comments/{commentId}/translation",
                        P_PID, C_PID)
                        .with(authedJwt())
                        .param("language", "vi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.translated_content").value("[VI] 댓글 본문"))
                .andExpect(jsonPath("$.data.translated_language").value("vi"));

        verify(commentTranslationService).getOrTranslate(P_PID, C_PID, "vi");
    }

    @Test
    @DisplayName("400: language 누락 → COMMON4001, service 미호출")
    void language_누락_400() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{postId}/comments/{commentId}/translation",
                        P_PID, C_PID).with(authedJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentTranslationService);
    }

    @Test
    @DisplayName("400: 미지원 언어 → COMMUNITY4003")
    void 미지원_언어_400() throws Exception {
        given(commentTranslationService.getOrTranslate(eq(P_PID), eq(C_PID), eq("ja")))
                .willThrow(new BusinessException(CommunityErrorCode.UNSUPPORTED_LANGUAGE));

        mockMvc.perform(get("/api/v1/community/posts/{postId}/comments/{commentId}/translation",
                        P_PID, C_PID).with(authedJwt()).param("language", "ja"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY4003"));
    }

    @Test
    @DisplayName("401: 토큰 없음 → AUTH4011")
    void 토큰_없음_401() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{postId}/comments/{commentId}/translation",
                        P_PID, C_PID).param("language", "vi"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(commentTranslationService);
    }

    @Test
    @DisplayName("404: 없는 게시글 → COMMUNITY4001")
    void 없는_게시글_404() throws Exception {
        given(commentTranslationService.getOrTranslate(eq(P_PID), eq(C_PID), eq("vi")))
                .willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        mockMvc.perform(get("/api/v1/community/posts/{postId}/comments/{commentId}/translation",
                        P_PID, C_PID).with(authedJwt()).param("language", "vi"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    @Test
    @DisplayName("404: 없는 댓글 또는 URL postId 불일치 → COMMUNITY4002")
    void 없는_댓글_404() throws Exception {
        given(commentTranslationService.getOrTranslate(eq(P_PID), eq(C_PID), eq("vi")))
                .willThrow(new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/community/posts/{postId}/comments/{commentId}/translation",
                        P_PID, C_PID).with(authedJwt()).param("language", "vi"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4002"));
    }
}
