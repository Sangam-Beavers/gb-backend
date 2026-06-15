package com.gb.admin.global.security;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * admin-service Cognito admin 그룹 인가 (RBAC) 검증 — 검표원이 검증한 토큰의 {@code cognito:groups} 가
 * {@link com.gb.common.security.CognitoGroupJwtAuthenticationConverter} 로 {@code ROLE_admin} 이 되고,
 * {@code /api/v1/admin/**} 가 {@code hasRole("admin")} 로 보호되는 전 구간 (토큰 → 디코더 → 변환기 → 인가) 을 본다.
 *
 * <p>{@code app.security.admin-group-enforced=true} 로 stage 동작 (검표원 + 그룹 인가) 을 켠다. 실제 Cognito
 * 가 없으므로 {@link JwtDecoder} 를 {@code @MockitoBean} 으로 대체해 임의 토큰 → Jwt 매핑을 주입한다
 * (AuthEntryPointTest와 동일 패턴). 외부 Admin 클라이언트는 컨텍스트 로딩용으로 목 처리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.admin-group-enforced=true")
class AdminAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.gb.admin.global.client.MemberAdminClient memberAdminClient;

    @MockitoBean
    private com.gb.admin.global.client.WalletAdminClient walletAdminClient;

    @MockitoBean
    private com.gb.admin.global.client.DocumentAdminClient documentAdminClient;

    @MockitoBean
    private com.gb.admin.global.client.CommunityAdminClient communityAdminClient;

    /** 콘솔 보호 경로 (인증 + admin 그룹 필요). */
    private static final String ADMIN_PATH = "/api/v1/admin/admins/me";

    private static Jwt.Builder baseJwt(String tokenValue) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject("cognito-sub")
                .claim("token_use", "access")
                .claim("public_id", "11111111-1111-1111-1111-111111111111");
    }

    @Test
    @DisplayName("admin 그룹 토큰 → 200 (인가 통과)")
    void admin그룹_허용() throws Exception {
        Jwt adminJwt = baseJwt("admin-token").claim("cognito:groups", List.of("admin")).build();
        given(jwtDecoder.decode("admin-token")).willReturn(adminJwt);

        mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("그룹 없는 일반 토큰 → 403 COMMON4031 (인증은 됐으나 admin 아님)")
    void 일반토큰_403() throws Exception {
        Jwt userJwt = baseJwt("user-token").build();
        given(jwtDecoder.decode("user-token")).willReturn(userJwt);

        mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON4031"));
    }

    @Test
    @DisplayName("토큰 없음 → 401 AUTH4011")
    void 토큰없음_401() throws Exception {
        mockMvc.perform(get(ADMIN_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));
    }

    @Test
    @DisplayName("위조/만료 토큰 → 401 AUTH4011 (BearerTokenAuthenticationFilter 경로)")
    void 위조토큰_401() throws Exception {
        given(jwtDecoder.decode(anyString()))
                .willThrow(new org.springframework.security.oauth2.jwt.BadJwtException("bad"));

        mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer forged.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));
    }

    @Test
    @DisplayName("공개 경로 (actuator/health) 는 토큰 없이 접근 가능 (Prometheus 스크레이프)")
    void 공개경로_무인증_허용() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
