package com.gb.community.domain.post.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.community.domain.post.dto.response.PostDetailResponse;
import com.gb.community.domain.post.dto.response.PostListResponse;
import com.gb.community.domain.post.service.PostService;
import com.gb.community.global.config.WebConfig;
import com.gb.community.global.exception.code.CommunityErrorCode;
import com.gb.community.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link PostController} HTTP wiring 검증 — URL/메서드, @Valid·@Validated, @RequestHeader,
 * @ResponseStatus(201), ApiResponse 래핑, snake_case 직렬화, BusinessException → ErrorResponse 변환.
 *
 * <p>{@code GlobalExceptionHandler}(common-exception)와 {@code SecurityConfig}(같은 서비스지만 슬라이스
 * 필터에서 제외)는 명시 {@code @Import}로 가져와 운영과 동일 경로(permitAll + 공용 예외 처리기)로 검증한다.
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
class PostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PostService postService;

    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String PID = "a1b2c3d4-0000-0000-0000-000000000001";

    /** 인증된 요청용 JWT 주입(public_id claim = USER). 컨트롤러는 이 claim으로 작성자를 식별한다. */
    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER));
    }

    /**
     * 토큰은 유효하나 {@code public_id} claim이 없는 JWT(=IdP Property Mapping 누락 시나리오).
     * CurrentUserPublicIdArgumentResolver가 AUTH4011로 fail-fast 하는 경로 검증용.
     */
    private static RequestPostProcessor jwtWithoutPublicId() {
        return jwt().jwt(j -> j.claim("sub", "no-mapping"));
    }

    // ----- POST /posts -----

    @Test
    @DisplayName("POST 201: 정상 작성 → 201 + snake_case 직렬화(author_is_verified/image_urls), service 호출")
    void create_정상_201() throws Exception {
        given(postService.createPost(eq(USER), any())).willReturn(stubDetail());

        mockMvc.perform(post("/api/v1/community/posts")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "JOB",
                                "title", "제목",
                                "content", "본문",
                                "image_urls", List.of("https://cdn.example.com/a.jpg")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value(PID))
                .andExpect(jsonPath("$.data.category").value("JOB"))
                .andExpect(jsonPath("$.data.author_nickname").value("Minh"))
                .andExpect(jsonPath("$.data.author_is_verified").value(true))
                .andExpect(jsonPath("$.data.author_temperature").value("GREEN"))
                .andExpect(jsonPath("$.data.image_urls").isArray())
                .andExpect(jsonPath("$.data.image_urls.length()").value(0))
                .andExpect(jsonPath("$.data.created_at").value("2026-05-30T04:15:30Z"));

        verify(postService).createPost(eq(USER), any());
    }

    @Test
    @DisplayName("POST 400: 필수값(title) 누락 → COMMON4001, service 미호출")
    void create_필수값_누락() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "JOB",
                                "content", "본문"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("POST 401: 토큰 없음 → AUTH4011, service 미호출")
    void create_토큰_없음_401() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "JOB",
                                "title", "제목",
                                "content", "본문"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("POST 401: 토큰은 유효하나 public_id claim 누락 → AUTH4011(resolver fail-fast), service 미호출")
    void create_publicIdClaim_누락_401() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts")
                        .with(jwtWithoutPublicId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "JOB",
                                "title", "제목",
                                "content", "본문"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("POST 401: 만료/위조 토큰 → AUTH4011 (BearerTokenAuthenticationFilter 경로), service 미호출")
    void create_만료토큰_401() throws Exception {
        // 실제 Authorization 헤더로 보내 BearerTokenAuthenticationFilter가 JwtDecoder.decode를 타게 한다
        // (jwt() 후처리기는 필터를 우회). decode가 만료 예외를 던지면 oauth2ResourceServer의 entry point가
        // AUTH4011로 응답해야 한다(빈 body 기본응답이면 회귀).
        // BadJwtException = "토큰이 나쁨"(만료·서명·형식) → InvalidBearerTokenException(401)으로 변환돼
        // entry point를 탄다. 일반 JwtException은 "디코더 장애"로 분류돼 500이 되므로 만료 재현엔 부적합.
        given(jwtDecoder.decode(anyString()))
                .willThrow(new BadJwtException("Jwt expired at 2026-06-02T02:27:55Z"));

        mockMvc.perform(post("/api/v1/community/posts")
                        .header("Authorization", "Bearer expired.jwt.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "JOB",
                                "title", "제목",
                                "content", "본문"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(postService);
    }

    // ----- GET /posts/{id} -----

    @Test
    @DisplayName("GET /{id} 200: 정상 단건 조회")
    void getPost_정상() throws Exception {
        given(postService.getPost(PID)).willReturn(stubDetail());

        mockMvc.perform(get("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.public_id").value(PID))
                .andExpect(jsonPath("$.data.author_is_verified").value(true));
    }

    @Test
    @DisplayName("GET /{id} 404: service가 COMMUNITY4001 던지면 → 404 + code")
    void getPost_없음_404() throws Exception {
        given(postService.getPost(PID))
                .willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        mockMvc.perform(get("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    // ----- GET /posts (list) -----

    @Test
    @DisplayName("GET 200: 목록 조회 → posts 배열 + 페이지 메타(snake_case)")
    void getPosts_정상() throws Exception {
        given(postService.getPosts(any(), any(), any(), eq(0), eq(20)))
                .willReturn(PostListResponse.of(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/community/posts")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.posts").isArray())
                .andExpect(jsonPath("$.data.total_elements").value(0));
    }

    @Test
    @DisplayName("GET 400: size 상한(100) 초과 → COMMON4001(@Max 위반), service 미호출")
    void getPosts_size_초과() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts")
                        .with(authedJwt())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(postService);
    }

    // ----- PATCH /posts/{id} -----

    @Test
    @DisplayName("PATCH 200: 정상 수정 → 200 + snake_case 직렬화, service가 경로 id로 호출됨")
    void updatePost_정상_200() throws Exception {
        given(postService.updatePost(eq(USER), eq(PID), any())).willReturn(stubDetail());

        mockMvc.perform(patch("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "수정"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value(PID))
                .andExpect(jsonPath("$.data.author_is_verified").value(true));

        verify(postService).updatePost(eq(USER), eq(PID), any());
    }

    @Test
    @DisplayName("PATCH 403: 타인 글(service가 COMMON4031) → 403 + code")
    void updatePost_타인_403() throws Exception {
        given(postService.updatePost(eq(USER), eq(PID), any()))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(patch("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "수정"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON4031"));
    }

    // ----- DELETE /posts/{id} -----

    @Test
    @DisplayName("DELETE 200: 정상 삭제 → 200 + data:null, service 호출")
    void deletePost_정상() throws Exception {
        mockMvc.perform(delete("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(postService).deletePost(USER, PID);
    }

    // ----- helpers -----

    private PostDetailResponse stubDetail() {
        return PostDetailResponse.builder()
                .publicId(PID)
                .category("JOB")
                .title("제목")
                .content("본문")
                .imageUrls(List.of())
                .authorNickname("Minh")
                .authorIsVerified(true)
                .authorTemperature("GREEN")
                .likeCount(0)
                .commentCount(0)
                .createdAt("2026-05-30T04:15:30Z")
                .updatedAt("2026-05-30T04:15:30Z")
                .build();
    }
}