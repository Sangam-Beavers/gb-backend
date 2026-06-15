package com.gb.appadmin.global.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * app-admin-service Cognito admin 그룹 인가 (RBAC) 검증 — {@code /api/v1/app-admin/admin/**} 는 admin 그룹만,
 * {@code /api/v1/app-admin/app/**} 는 공개임을 전 구간 (토큰 → 디코더 → 변환기 → 인가) 으로 본다.
 *
 * <p>관리자 경로는 항상 admin 그룹으로 보호된다 (플래그 불필요). {@code spring.sql.init.mode=never} 로
 * dev 전용 seed (data.sql) 가 H2에 로드되지 않게 한다 (테스트는 빈 테이블이면 충분). {@link JwtDecoder} 는
 * 목으로 임의 토큰 → Jwt 매핑을 주입한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.sql.init.mode=never")
class AppAdminAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String ADMIN_PATH = "/api/v1/app-admin/admin/faqs";
    private static final String PUBLIC_PATH = "/api/v1/app-admin/app/faqs";

    private static Jwt.Builder baseJwt(String tokenValue) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject("cognito-sub")
                .claim("token_use", "access")
                .claim("public_id", "22222222-2222-2222-2222-222222222222");
    }

    @Test
    @DisplayName("admin 그룹 토큰 → 200 (관리자 경로 인가 통과)")
    void admin그룹_허용() throws Exception {
        Jwt adminJwt = baseJwt("admin-token").claim("cognito:groups", List.of("admin")).build();
        given(jwtDecoder.decode("admin-token")).willReturn(adminJwt);

        mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("그룹 없는 일반 토큰 → 관리자 경로 403 COMMON4031")
    void 일반토큰_403() throws Exception {
        Jwt userJwt = baseJwt("user-token").build();
        given(jwtDecoder.decode("user-token")).willReturn(userJwt);

        mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON4031"));
    }

    @Test
    @DisplayName("토큰 없음 → 관리자 경로 401 AUTH4011")
    void 토큰없음_401() throws Exception {
        mockMvc.perform(get(ADMIN_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));
    }

    @Test
    @DisplayName("위조/만료 토큰 → 401 AUTH4011")
    void 위조토큰_401() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("bad"));

        mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer forged.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));
    }

    @Test
    @DisplayName("앱 공개 경로(/app/**)는 토큰 없이 200")
    void 공개경로_무인증_허용() throws Exception {
        mockMvc.perform(get(PUBLIC_PATH))
                .andExpect(status().isOk());
    }
}
