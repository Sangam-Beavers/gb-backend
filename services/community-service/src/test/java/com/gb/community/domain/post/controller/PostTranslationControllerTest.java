package com.gb.community.domain.post.controller;

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
import com.gb.community.domain.post.dto.response.PostTranslationResponse;
import com.gb.community.domain.post.service.PostService;
import com.gb.community.domain.post.service.PostTranslationService;
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
 * {@link PostController}의 번역 보기 엔드포인트 — HTTP wiring 검증.
 *
 * <p>{@code GET /api/v1/community/posts/{id}/translation?language=...} 경로의 200/400/401/404 케이스를 본다.
 * {@code PostControllerTest}와 같은 컨트롤러를 띄우지만 번역 시나리오에 집중해 별도 파일로 분리한다.
 */
@WebMvcTest(PostController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.community.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class PostTranslationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // PostController가 PostService도 함께 의존하므로 가려야 컨텍스트 로딩 가능.
    @MockitoBean
    private PostService postService;

    @MockitoBean
    private PostTranslationService postTranslationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String PID = "a1b2c3d4-0000-0000-0000-000000000001";

    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER));
    }

    @Test
    @DisplayName("200: 정상 번역 — snake_case 직렬화(translated_*), Service에 (pid, language) 전달")
    void 정상_200() throws Exception {
        given(postTranslationService.getOrTranslate(eq(PID), eq("vi")))
                .willReturn(PostTranslationResponse.builder()
                        .translatedTitle("[VI] 최저임금")
                        .translatedContent("[VI] 시급이 낮아요")
                        .translatedLanguage("vi")
                        .build());

        mockMvc.perform(get("/api/v1/community/posts/{id}/translation", PID)
                        .with(authedJwt())
                        .param("language", "vi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.translated_title").value("[VI] 최저임금"))
                .andExpect(jsonPath("$.data.translated_content").value("[VI] 시급이 낮아요"))
                .andExpect(jsonPath("$.data.translated_language").value("vi"));

        verify(postTranslationService).getOrTranslate(PID, "vi");
    }

    @Test
    @DisplayName("400: language 누락 → COMMON4001 (@NotBlank/필수 위반), service 미호출")
    void language_누락_400() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}/translation", PID)
                        .with(authedJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(postTranslationService);
    }

    @Test
    @DisplayName("400: 미지원 언어(ja) → COMMUNITY4003 (service가 던짐)")
    void 미지원_언어_400() throws Exception {
        given(postTranslationService.getOrTranslate(eq(PID), eq("ja")))
                .willThrow(new BusinessException(CommunityErrorCode.UNSUPPORTED_LANGUAGE));

        mockMvc.perform(get("/api/v1/community/posts/{id}/translation", PID)
                        .with(authedJwt())
                        .param("language", "ja"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY4003"));
    }

    @Test
    @DisplayName("400: 본문 5000자 초과 → COMMUNITY4004")
    void 본문_초과_400() throws Exception {
        given(postTranslationService.getOrTranslate(eq(PID), eq("vi")))
                .willThrow(new BusinessException(CommunityErrorCode.CONTENT_TOO_LONG));

        mockMvc.perform(get("/api/v1/community/posts/{id}/translation", PID)
                        .with(authedJwt())
                        .param("language", "vi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY4004"));
    }

    @Test
    @DisplayName("401: 토큰 없음 → AUTH4011, service 미호출")
    void 토큰_없음_401() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}/translation", PID)
                        .param("language", "vi"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(postTranslationService);
    }

    @Test
    @DisplayName("404: 없는 게시글 → COMMUNITY4001 (service가 던짐)")
    void 없는_글_404() throws Exception {
        given(postTranslationService.getOrTranslate(eq(PID), eq("vi")))
                .willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        mockMvc.perform(get("/api/v1/community/posts/{id}/translation", PID)
                        .with(authedJwt())
                        .param("language", "vi"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }
}
