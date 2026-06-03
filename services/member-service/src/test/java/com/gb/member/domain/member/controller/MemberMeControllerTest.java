package com.gb.member.domain.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.member.domain.member.dto.response.LanguageResponse;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.config.WebConfig;
import com.gb.member.global.exception.code.MemberErrorCode;
import com.gb.member.global.security.CurrentUserPublicIdArgumentResolver;
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
 * {@link MemberMeController}의 HTTP wiring 검증 — URL/메서드, @Valid(@NotBlank), @CurrentUserPublicId 주입,
 * ApiResponse 래핑, BusinessException → ErrorResponse 변환까지.
 *
 * <p>member-service는 {@code @SpringBootTest}가 메일 발송 빈(JavaMailSender) 누락으로 실패하므로,
 * 메일을 쓰는 {@link MemberService}를 mock으로 가리는 {@code @WebMvcTest} 슬라이스로 검증한다(전체 컨텍스트
 * 미로딩). {@link com.gb.common.exception.handler.GlobalExceptionHandler}는 {@code common-exception} 모듈에
 * 있어 슬라이스 기본 스캔에 잡히지 않고, {@link com.gb.member.global.config.SecurityConfig}는 같은 서비스지만
 * 슬라이스 컴포넌트 필터에서 제외되므로(spring-security가 classpath에 있으면 기본 필터가 모든 요청을 차단),
 * 두 빈과 WebConfig·ArgumentResolver를 명시 {@code @Import}로 가져와 운영과 동일 경로에서 검증한다.
 */
@WebMvcTest(MemberMeController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.member.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class MemberMeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private MemberService memberService;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final String USER_ID = "test-uuid-1234";

    /** 인증된 요청용 JWT 주입(public_id claim = USER_ID). 컨트롤러는 이 claim으로 사용자를 식별한다. */
    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER_ID));
    }

    /**
     * 토큰은 유효하나 {@code public_id} claim이 없는 JWT(=IdP Property Mapping 누락 시나리오).
     * CurrentUserPublicIdArgumentResolver가 AUTH4011로 fail-fast 하는 경로 검증용.
     */
    private static RequestPostProcessor jwtWithoutPublicId() {
        return jwt().jwt(j -> j.claim("sub", "no-mapping"));
    }

    // --- GET /language ---

    @Test
    @DisplayName("GET /language 200: 정상 조회 시 ApiResponse(success=true) + data.language 반환")
    void getLanguage_정상() throws Exception {
        given(memberService.getLanguage(USER_ID))
                .willReturn(LanguageResponse.builder().language("ko").build());

        mockMvc.perform(get("/api/v1/members/me/language")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.language").value("ko"));

        verify(memberService).getLanguage(USER_ID);
    }

    @Test
    @DisplayName("GET /language 404: service가 MEMBER4001(없는 회원) 던지면 → 404 + code")
    void getLanguage_없는_회원_404() throws Exception {
        willThrow(new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND))
                .given(memberService).getLanguage(anyString());

        mockMvc.perform(get("/api/v1/members/me/language")
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MEMBER4001"));
    }

    @Test
    @DisplayName("GET /language 401: 토큰 없음 → AUTH4011, service 미호출")
    void getLanguage_토큰_없음_401() throws Exception {
        mockMvc.perform(get("/api/v1/members/me/language"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(memberService);
    }

    @Test
    @DisplayName("GET /language 401: 만료/위조 토큰 → AUTH4011 (BearerTokenAuthenticationFilter 경로), service 미호출")
    void getLanguage_만료토큰_401() throws Exception {
        // 실제 Authorization 헤더로 보내 BearerTokenAuthenticationFilter가 JwtDecoder.decode를 타게 한다
        // (jwt() 후처리기는 필터를 우회하므로 이 경로를 검증 못 함). BadJwtException(만료·서명·형식)은
        // InvalidBearerTokenException(401)으로 변환돼 oauth2ResourceServer의 entry point가 AUTH4011로 응답한다.
        given(jwtDecoder.decode(anyString()))
                .willThrow(new BadJwtException("Jwt expired at 2026-06-02T02:27:55Z"));

        mockMvc.perform(get("/api/v1/members/me/language")
                        .header("Authorization", "Bearer expired.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(memberService);
    }

    @Test
    @DisplayName("GET /language 401: 토큰은 유효하나 public_id claim 누락 → AUTH4011(resolver fail-fast), service 미호출")
    void getLanguage_publicIdClaim_누락_401() throws Exception {
        mockMvc.perform(get("/api/v1/members/me/language")
                        .with(jwtWithoutPublicId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(memberService);
    }

    // --- PATCH /language ---

    @Test
    @DisplayName("PATCH /language 200: 정상 변경 → data.language 변경값 반환 + service 호출")
    void updateLanguage_정상() throws Exception {
        given(memberService.updateLanguage(USER_ID, "vi"))
                .willReturn(LanguageResponse.builder().language("vi").build());

        mockMvc.perform(patch("/api/v1/members/me/language")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "vi"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.language").value("vi"));

        verify(memberService).updateLanguage(USER_ID, "vi");
    }

    @Test
    @DisplayName("PATCH /language 400: language 빈 문자열 → @NotBlank 위반 → COMMON4001, service 미호출")
    void updateLanguage_빈_language() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me/language")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(memberService, never()).updateLanguage(any(), any());
    }

    @Test
    @DisplayName("PATCH /language 400: language 필드 누락(빈 객체) → @NotBlank 위반 → COMMON4001, service 미호출")
    void updateLanguage_language_누락() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me/language")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(memberService, never()).updateLanguage(any(), any());
    }

    @Test
    @DisplayName("PATCH /language 401: 토큰 없음 → AUTH4011, service 미호출")
    void updateLanguage_토큰_없음_401() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me/language")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "vi"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verify(memberService, never()).updateLanguage(any(), any());
    }

    // --- DELETE /me ---

    @Test
    @DisplayName("DELETE /me 200: 정상 탈퇴 → success=true + data null, service.withdraw 호출")
    void withdraw_정상() throws Exception {
        // void 반환 → stub 불필요(기본 do-nothing).
        mockMvc.perform(delete("/api/v1/members/me")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(memberService).withdraw(USER_ID);
    }

    @Test
    @DisplayName("DELETE /me 404: service가 MEMBER4001(없는 회원) 던지면 → 404 + code")
    void withdraw_없는_회원_404() throws Exception {
        willThrow(new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND))
                .given(memberService).withdraw(eq(USER_ID));

        mockMvc.perform(delete("/api/v1/members/me")
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER4001"));
    }

    @Test
    @DisplayName("DELETE /me 401: 토큰 없음 → AUTH4011, service 미호출")
    void withdraw_토큰_없음_401() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verify(memberService, never()).withdraw(anyString());
    }
}
