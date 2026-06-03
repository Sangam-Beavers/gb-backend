package com.gb.community.domain.qna.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.community.domain.qna.dto.response.QnaListResponse;
import com.gb.community.domain.qna.dto.response.QnaPostResponse;
import com.gb.community.domain.qna.service.QnaService;
import com.gb.community.global.config.WebConfig;
import com.gb.community.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link QnaController} HTTP wiring 검증 — URL/메서드, @Validated, ApiResponse 래핑, snake_case 직렬화,
 * BusinessException → ErrorResponse 변환.
 *
 * <p><b>가장 중요한 검증: permitAll.</b> 주요 QnA 목록({@code GET /api/v1/community/qna})은 이 도메인에서
 * 유일하게 <b>인증 불필요</b>한 공개 엔드포인트다(SecurityConfig). 이 정책은 보안 필터 체인을 거쳐야만
 * 드러나므로 단위/서비스 테스트로는 못 잡고 웹 레벨에서만 검증된다 — 토큰 없이 200이 나와야 한다.
 *
 * <p>{@code GlobalExceptionHandler}(common-exception)와 {@code SecurityConfig}(슬라이스 필터에서 제외)는
 * 명시 {@code @Import}로 가져와 운영과 동일 경로로 검증한다(Post/CommentControllerTest와 동일 패턴).
 */
@WebMvcTest(QnaController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.community.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class QnaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QnaService qnaService;

    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("GET 200 (permitAll): 토큰 없이도 조회 가능 — 이 도메인 유일의 공개 엔드포인트, service 도달")
    void getQnaPosts_비로그인_200_permitAll() throws Exception {
        // size 미입력 → 컨트롤러 기본값 5가 서비스로 전달되는지 eq(5)로 못 박는다(기본값 변경 시 회귀 감지).
        given(qnaService.getQnaPosts(any(), eq(5)))
                .willReturn(QnaListResponse.of(List.of(
                        stub("p1", "E-9 비자로 근무지 변경이 가능한가요?", 7, "2026-05-20T09:00:00Z"))));

        mockMvc.perform(get("/api/v1/community/qna")) // ← Authorization 헤더 없음(비로그인)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.posts").isArray())
                .andExpect(jsonPath("$.data.posts[0].public_id").value("p1"));

        verify(qnaService).getQnaPosts(any(), eq(5));
    }

    @Test
    @DisplayName("GET 200: data.posts 배열 snake_case 직렬화 + 서비스가 준 답변수 DESC 순서 보존")
    void getQnaPosts_snake_case_및_답변수DESC순서() throws Exception {
        given(qnaService.getQnaPosts(any(), anyInt()))
                .willReturn(QnaListResponse.of(List.of(
                        stub("p1", "E-9 비자로 근무지 변경이 가능한가요?", 7, "2026-05-20T09:00:00Z"),
                        stub("p2", "건강보험 피부양자 등록은 어떻게 하나요?", 4, "2026-05-18T14:20:00Z"))));

        mockMvc.perform(get("/api/v1/community/qna")
                        .param("category", "QUESTION")
                        .param("size", "5"))
                .andExpect(status().isOk())
                // 답변수 DESC 순서가 직렬화에 그대로 실린다(7 → 4).
                .andExpect(jsonPath("$.data.posts[0].public_id").value("p1"))
                .andExpect(jsonPath("$.data.posts[0].title").value("E-9 비자로 근무지 변경이 가능한가요?"))
                .andExpect(jsonPath("$.data.posts[0].comment_count").value(7)) // camelCase → snake_case
                .andExpect(jsonPath("$.data.posts[0].created_at").value("2026-05-20T09:00:00Z"))
                .andExpect(jsonPath("$.data.posts[1].public_id").value("p2"))
                .andExpect(jsonPath("$.data.posts[1].comment_count").value(4));
    }

    @Test
    @DisplayName("GET 400: size 하한(1) 미만(0) → COMMON4001, service 미호출")
    void getQnaPosts_size_하한위반_400() throws Exception {
        mockMvc.perform(get("/api/v1/community/qna").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(qnaService);
    }

    @Test
    @DisplayName("GET 400: size 상한(100) 초과(101) → COMMON4001, service 미호출")
    void getQnaPosts_size_상한위반_400() throws Exception {
        mockMvc.perform(get("/api/v1/community/qna").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(qnaService);
    }

    @Test
    @DisplayName("GET 400: category 길이 30 초과(31자, @Size) → COMMON4001, service 미호출")
    void getQnaPosts_category_길이위반_400() throws Exception {
        mockMvc.perform(get("/api/v1/community/qna").param("category", "A".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(qnaService);
    }

    @Test
    @DisplayName("GET 400: 잘못된 카테고리(enum에 없음) → service가 COMMON4001 던지면 400으로 변환")
    void getQnaPosts_잘못된카테고리_400() throws Exception {
        given(qnaService.getQnaPosts(any(), eq(5)))
                .willThrow(new BusinessException(CommonErrorCode.INVALID_REQUEST));

        mockMvc.perform(get("/api/v1/community/qna").param("category", "INVALID_CAT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        // "INVALID_CAT"(11자)는 @Size(30) 가드를 통과해 실제 서비스(enum 파싱)까지 도달함을 못 박는다
        // — 컨트롤러 검증 단계 거부와 구분(둘 다 COMMON4001이라 상태/코드만으론 구분 불가).
        // size 미입력이므로 기본값 5도 함께 eq(5)로 검증한다.
        verify(qnaService).getQnaPosts(eq("INVALID_CAT"), eq(5));
    }

    // ----- helpers -----

    private QnaPostResponse stub(String publicId, String title, int commentCount, String createdAt) {
        return QnaPostResponse.builder()
                .publicId(publicId)
                .title(title)
                .commentCount(commentCount)
                .createdAt(createdAt)
                .build();
    }
}
