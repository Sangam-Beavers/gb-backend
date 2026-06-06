package com.gb.community.domain.post.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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
import com.gb.community.domain.post.dto.response.PostSummaryResponse;
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
    @DisplayName("POST 201: 정상 작성 → 201 + snake_case 직렬화(author_is_verified), service 호출")
    void create_정상_201() throws Exception {
        given(postService.createPost(eq(USER), any())).willReturn(stubDetail());

        mockMvc.perform(post("/api/v1/community/posts")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "JOB",
                                "title", "제목",
                                "content", "본문"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value(PID))
                .andExpect(jsonPath("$.data.category").value("JOB"))
                .andExpect(jsonPath("$.data.author_nickname").value("Minh"))
                .andExpect(jsonPath("$.data.author_is_verified").value(true))
                .andExpect(jsonPath("$.data.is_author").value(true)) // 작성 응답은 항상 true(명세 §2)
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
    @DisplayName("POST 400: content 10,001자(@Size 초과) → COMMON4001, service 미호출(TEXT 컬럼 INSERT 전 차단)")
    void create_content_상한초과() throws Exception {
        mockMvc.perform(post("/api/v1/community/posts")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "JOB",
                                "title", "제목",
                                "content", "가".repeat(10_001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("PATCH 400: content 10,001자(@Size 초과) → COMMON4001, service 미호출(작성과 동일 상한)")
    void update_content_상한초과() throws Exception {
        mockMvc.perform(patch("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "가".repeat(10_001)))))
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
    @DisplayName("GET /{id} 200: 정상 단건 조회 — is_author/is_liked가 정확히 그 키로 직렬화(Boolean 게터 함정 회귀 가드)")
    void getPost_정상() throws Exception {
        given(postService.getPost(USER, PID)).willReturn(stubDetail());

        mockMvc.perform(get("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.public_id").value(PID))
                .andExpect(jsonPath("$.data.author_is_verified").value(true))
                // primitive boolean이었다면 'is'가 떨어져 $.data.author로 나간다 — 키 이름 자체를 단언.
                .andExpect(jsonPath("$.data.is_author").value(true))
                .andExpect(jsonPath("$.data.author").doesNotExist())
                // is_liked도 동일 함정 가드 — primitive면 $.data.liked로 어긋난다(프론트는 is_liked를 읽음).
                .andExpect(jsonPath("$.data.is_liked").value(true))
                .andExpect(jsonPath("$.data.liked").doesNotExist());

        verify(postService).getPost(USER, PID); // 요청자(public_id claim)가 서비스로 전달됨
    }

    @Test
    @DisplayName("GET /{id} 401: 토큰은 유효하나 public_id claim 누락 → AUTH4011 — 단건도 is_author 계산에 본인 식별을 쓰므로 resolver fail-fast")
    void getPost_publicIdClaim_누락_401() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts/{id}", PID)
                        .with(jwtWithoutPublicId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("GET /{id} 404: service가 COMMUNITY4001 던지면 → 404 + code")
    void getPost_없음_404() throws Exception {
        given(postService.getPost(USER, PID))
                .willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        mockMvc.perform(get("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    // ----- GET /posts (list) -----

    @Test
    @DisplayName("GET 200: 목록 조회 → posts 배열 + 페이지 메타(snake_case), 요청자(public_id)가 서비스로 전달, "
            + "타인 글 항목의 is_author=false가 정확히 'is_author' 키로 직렬화")
    void getPosts_정상() throws Exception {
        // 타인 글 1건 — 목록 항목(PostSummaryResponse)의 is_author=false 직렬화까지 검증한다
        // (Boolean false도 NON_NULL류 정책에 걸리지 않고 키가 나가야 프론트가 버튼 비노출을 판단할 수 있다).
        PostSummaryResponse otherPost = PostSummaryResponse.builder()
                .publicId(PID)
                .category("JOB")
                .title("제목")
                .contentPreview("미리보기")
                .authorNickname("Sokha")
                .isAuthor(false)
                .likeCount(0)
                .commentCount(0)
                .createdAt("2026-05-30T04:15:30Z")
                .build();
        given(postService.getPosts(eq(USER), any(), any(), any(), eq(0), eq(20)))
                .willReturn(PostListResponse.of(List.of(otherPost), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/community/posts")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.posts").isArray())
                // primitive boolean이었다면 'is'가 떨어져 author 키로 나간다 — 키 이름 자체를 단언.
                .andExpect(jsonPath("$.data.posts[0].is_author").value(false))
                .andExpect(jsonPath("$.data.posts[0].author").doesNotExist())
                .andExpect(jsonPath("$.data.total_elements").value(1));

        verify(postService).getPosts(eq(USER), any(), any(), any(), eq(0), eq(20));
    }

    @Test
    @DisplayName("GET 401: 토큰은 유효하나 public_id claim 누락 → AUTH4011 — 목록도 이제 본인 식별(is_author)을 쓰므로 resolver fail-fast")
    void getPosts_publicIdClaim_누락_401() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts")
                        .with(jwtWithoutPublicId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(postService);
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

    @Test
    @DisplayName("GET 400: page 상한(10000) 초과 → COMMON4001(@Max 위반), service 미호출")
    void getPosts_page_초과() throws Exception {
        mockMvc.perform(get("/api/v1/community/posts")
                        .with(authedJwt())
                        .param("page", "10001"))
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
                .andExpect(jsonPath("$.data.author_is_verified").value(true))
                .andExpect(jsonPath("$.data.is_author").value(true)); // 수정은 본인 검증 통과 흐름 — 항상 true

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

    @Test
    @DisplayName("DELETE 404: 없는/이미삭제 글(service가 COMMUNITY4001) → 404 + code")
    void deletePost_없음_404() throws Exception {
        willThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND))
                .given(postService).deletePost(USER, PID);

        mockMvc.perform(delete("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMUNITY4001"));
    }

    @Test
    @DisplayName("DELETE 403: 타인 글(service가 COMMON4031) → 403 + code")
    void deletePost_타인_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .given(postService).deletePost(USER, PID);

        mockMvc.perform(delete("/api/v1/community/posts/{id}", PID)
                        .with(authedJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON4031"));
    }

    // ----- helpers -----

    private PostDetailResponse stubDetail() {
        return PostDetailResponse.builder()
                .publicId(PID)
                .category("JOB")
                .title("제목")
                .content("본문")
                .authorNickname("Minh")
                .authorIsVerified(true)
                .isAuthor(true)
                .isLiked(true) // true로 둬야 is_liked 직렬화 키 단언이 '키 부재=실패'로 동작(false면 값 혼동)
                .likeCount(0)
                .commentCount(0)
                .createdAt("2026-05-30T04:15:30Z")
                .updatedAt("2026-05-30T04:15:30Z")
                .build();
    }
}