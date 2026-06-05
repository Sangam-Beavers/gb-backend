package com.gb.community.domain.comment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.community.domain.comment.dto.request.CreateCommentRequest;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.dto.response.CommentResponse;
import com.gb.community.domain.comment.service.CommentService;
import com.gb.community.global.config.WebConfig;
import com.gb.community.global.exception.code.CommunityErrorCode;
import com.gb.community.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
        com.gb.community.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class CommentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CommentService commentService;

    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String PID = "a1b2c3d4-0000-0000-0000-000000000001";
    private static final String CID = "c1d2e3f4-0000-0000-0000-000000000001";
    private static final String VALID_CONTENT = "좋은 정보 감사합니다!";

    /** 인증된 요청용 JWT 주입(public_id claim = USER). getComments도 is_author 계산에 본인 식별을 쓴다. */
    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER));
    }

    /**
     * 토큰은 유효하나 {@code public_id} claim이 없는 JWT(=IdP Property Mapping 누락 시나리오).
     * CurrentUserPublicIdArgumentResolver가 AUTH4011로 fail-fast 하는 경로 검증용(PostControllerTest와 동일).
     */
    private static RequestPostProcessor jwtWithoutPublicId() {
        return jwt().jwt(j -> j.claim("sub", "no-mapping"));
    }

    @Test
    @DisplayName("GET 200: 댓글 목록 → comments 배열 + 페이지 메타(snake_case), is_author가 정확히 'is_author' 키로 직렬화")
    void getComments_정상() throws Exception {
        given(commentService.getComments(eq(PID), eq(USER), eq(0), eq(20)))
                .willReturn(CommentListResponse.of(List.of(stubComment()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID)
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.comments").isArray())
                .andExpect(jsonPath("$.data.comments[0].public_id").value(CID))
                .andExpect(jsonPath("$.data.comments[0].post_public_id").value(PID))
                .andExpect(jsonPath("$.data.comments[0].parent_comment_public_id").value(nullValue()))
                .andExpect(jsonPath("$.data.comments[0].content").value("댓글 내용"))
                .andExpect(jsonPath("$.data.comments[0].author_nickname").value("Minh"))
                .andExpect(jsonPath("$.data.comments[0].author_is_verified").value(true))
                // primitive boolean이었다면 'is'가 떨어져 author 키로 나간다 — 키 이름 자체를 단언.
                .andExpect(jsonPath("$.data.comments[0].is_author").value(true))
                .andExpect(jsonPath("$.data.comments[0].author").doesNotExist())
                .andExpect(jsonPath("$.data.comments[0].created_at").value("2026-05-26T04:15:30Z"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total_elements").value(1))
                .andExpect(jsonPath("$.data.total_pages").value(1));

        verify(commentService).getComments(PID, USER, 0, 20); // 요청자(public_id claim)가 서비스로 전달됨
    }

    @Test
    @DisplayName("GET 404: service가 COMMUNITY4001 던지면 → 404 + code")
    void getComments_게시글없음_404() throws Exception {
        given(commentService.getComments(eq(PID), eq(USER), any(Integer.class), any(Integer.class)))
                .willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID)
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    @Test
    @DisplayName("GET 400: size 상한(100) 초과 → COMMON4001(@Max 위반), service 미호출")
    void getComments_size_초과() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID)
                        .with(authedJwt())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("GET 400: page 상한(10000) 초과 → COMMON4001(@Max 위반), service 미호출")
    void getComments_page_초과() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID)
                        .with(authedJwt())
                        .param("page", "10001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("GET 401: 토큰 없음 → AUTH4011, service 미호출")
    void getComments_토큰_없음_401() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("GET 401: 토큰은 유효하나 public_id claim 누락 → AUTH4011 — 댓글 목록도 is_author 계산에 본인 식별을 쓰므로 resolver fail-fast")
    void getComments_publicIdClaim_누락_401() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}/comments", PID)
                        .with(jwtWithoutPublicId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(commentService);
    }

    // ----- POST /posts/{id}/comments (댓글 작성, CC-T1) -----

    @Test
    @DisplayName("POST 201: 정상 작성 → 201 + CommentResponse snake_case 직렬화, service가 (postId, user, request)로 호출됨")
    void createComment_정상_201() throws Exception {
        given(commentService.createComment(eq(PID), eq(USER), any()))
                .willReturn(stubComment());

        mockMvc.perform(post("/api/v1/community/posts/{id}/comments", PID)
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value(CID))
                .andExpect(jsonPath("$.data.post_public_id").value(PID))
                .andExpect(jsonPath("$.data.parent_comment_public_id").value(nullValue()))
                .andExpect(jsonPath("$.data.content").value("댓글 내용"))
                .andExpect(jsonPath("$.data.author_nickname").value("Minh"))
                .andExpect(jsonPath("$.data.author_is_verified").value(true))
                .andExpect(jsonPath("$.data.is_author").value(true)) // 작성 응답은 항상 true(명세 §6)
                .andExpect(jsonPath("$.data.created_at").value("2026-05-26T04:15:30Z"));

        // @RequestBody 바인딩 검증: 보낸 content가 그대로 서비스로 전달됐는지 캡처해 확인
        // (응답의 content는 mock(stubComment)에서 오므로 요청 바인딩은 별도 검증해야 한다).
        ArgumentCaptor<CreateCommentRequest> captor = ArgumentCaptor.forClass(CreateCommentRequest.class);
        verify(commentService).createComment(eq(PID), eq(USER), captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo(VALID_CONTENT);
    }

    @Test
    @DisplayName("POST 400: content 빈값(@NotBlank 위반) → COMMON4001, service 미호출")
    void createComment_content_빈값_400() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts/{id}/comments", PID)
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("POST 400: content 2000자 초과(@Size 위반) → COMMON4001, service 미호출")
    void createComment_content_초과_400() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts/{id}/comments", PID)
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "a".repeat(2001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("POST 400: path public_id 36자 초과(@Size 위반) → COMMON4001, service 미호출")
    void createComment_path_size_위반_400() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts/{id}/comments", "a".repeat(37))
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("POST 404: 게시글 없음(service COMMUNITY4001) → 404 + code")
    void createComment_게시글없음_404() throws Exception {
        given(commentService.createComment(eq(PID), eq(USER), any()))
                .willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        mockMvc.perform(post("/api/v1/community/posts/{id}/comments", PID)
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    @Test
    @DisplayName("POST 401: 토큰 없음 → AUTH4011, service 미호출")
    void createComment_토큰없음_401() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts/{id}/comments", PID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(commentService);
    }

    // ----- DELETE /posts/{postId}/comments/{commentId} (댓글 삭제, CC-T2) -----

    @Test
    @DisplayName("DELETE 200: 정상 삭제 → 200 + data:null, service가 (postId, commentId, user)로 호출됨")
    void deleteComment_정상_200() throws Exception {
        mockMvc.perform(delete("/api/v1/community/posts/{postId}/comments/{commentId}", PID, CID)
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(commentService).deleteComment(PID, CID, USER);
    }

    @Test
    @DisplayName("DELETE 403: 본인 아님(service COMMON4031) → 403 + code")
    void deleteComment_타인_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .given(commentService).deleteComment(eq(PID), eq(CID), eq(USER));

        mockMvc.perform(delete("/api/v1/community/posts/{postId}/comments/{commentId}", PID, CID)
                        .with(authedJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON4031"));
    }

    @Test
    @DisplayName("DELETE 404: 없는/이미삭제/URL불일치 댓글(service COMMUNITY4002) → 404 + code")
    void deleteComment_댓글없음_404() throws Exception {
        willThrow(new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND))
                .given(commentService).deleteComment(eq(PID), eq(CID), eq(USER));

        mockMvc.perform(delete("/api/v1/community/posts/{postId}/comments/{commentId}", PID, CID)
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4002"));
    }

    @Test
    @DisplayName("DELETE 404: 게시글 없음(service COMMUNITY4001) → 404 + code")
    void deleteComment_게시글없음_404() throws Exception {
        willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND))
                .given(commentService).deleteComment(eq(PID), eq(CID), eq(USER));

        mockMvc.perform(delete("/api/v1/community/posts/{postId}/comments/{commentId}", PID, CID)
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    @Test
    @DisplayName("DELETE 400: commentId 36자 초과(@Size 위반) → COMMON4001, service 미호출")
    void deleteComment_path_size_위반_400() throws Exception {
        mockMvc.perform(delete("/api/v1/community/posts/{postId}/comments/{commentId}", PID, "a".repeat(37))
                        .with(authedJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("DELETE 401: 토큰 없음 → AUTH4011, service 미호출")
    void deleteComment_토큰없음_401() throws Exception {
        mockMvc.perform(delete("/api/v1/community/posts/{postId}/comments/{commentId}", PID, CID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(commentService);
    }

    // ----- helpers -----

    /** 작성 검증 통과용 정상 요청 본문(content만 존재). */
    private String validBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of("content", VALID_CONTENT));
    }

    private CommentResponse stubComment() {
        return CommentResponse.builder()
                .publicId(CID)
                .postPublicId(PID)
                .parentCommentPublicId(null)
                .content("댓글 내용")
                .authorNickname("Minh")
                .authorIsVerified(true)
                .isAuthor(true)
                .createdAt("2026-05-26T04:15:30Z")
                .build();
    }
}
