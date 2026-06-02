package com.gb.member.global.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 방식 B 인증 실패 응답이 표준 포맷(AUTH4011)으로 나오는지 검증한다.
 *
 * <p>특히 만료/위조 토큰은 {@code BearerTokenAuthenticationFilter}가 처리하는데, 이 필터는
 * {@code exceptionHandling}의 entry point가 아니라 {@code oauth2ResourceServer} DSL에 등록된
 * entry point를 쓴다. 후자를 빠뜨리면 Spring 기본(빈 body + WWW-Authenticate)으로 응답돼 AUTH4011을
 * 벗어나므로, 그 회귀를 막는다. (토큰 누락은 exceptionHandling 경로 — 별도.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthEntryPointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** 인증이 필요한 임의 보호 경로(미구현이어도 SecurityConfig가 인증을 요구 → 필터 단계에서 거부). */
    private static final String PROTECTED_PATH = "/api/v1/members/me";

    @Test
    @DisplayName("만료/위조 토큰 → AUTH4011 (BearerTokenAuthenticationFilter 경로)")
    void 만료토큰_AUTH4011() throws Exception {
        // BadJwtException = "토큰이 나쁨"(만료·서명·형식) → InvalidBearerTokenException(401). 일반 JwtException은
        // "디코더 장애"로 분류돼 500이 되므로 만료 재현엔 부적합.
        given(jwtDecoder.decode(anyString()))
                .willThrow(new BadJwtException("Jwt expired at 2026-06-02T02:27:55Z"));

        mockMvc.perform(get(PROTECTED_PATH)
                        .header("Authorization", "Bearer expired.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));
    }

    @Test
    @DisplayName("토큰 없음 → AUTH4011 (exceptionHandling 경로)")
    void 토큰없음_AUTH4011() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));
    }
}
