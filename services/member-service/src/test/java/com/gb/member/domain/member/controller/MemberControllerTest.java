package com.gb.member.domain.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.config.WebConfig;
import com.gb.member.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link MemberController}(회원가입 /api/v1/auth/register)의 HTTP wiring 검증 — 특히 약관 동의 필드의
 * Bean Validation(@NotNull + @AssertTrue)이 웹 계층에서 동작해 400(COMMON4001)으로 떨어지는지 확인한다.
 * 동의 검증은 컨트롤러 @Valid를 거쳐야만 발화하므로 서비스 단위 테스트로는 안 잡혀 슬라이스 테스트로 둔다.
 *
 * <p>{@code /auth/register}는 SecurityConfig에서 permitAll이라 인증 없이 본문 검증만 탄다.
 * member-service 슬라이스 구성은 {@link MemberMeControllerTest} 주석 참조(GlobalExceptionHandler·SecurityConfig
 * 등을 명시 @Import).
 */
@WebMvcTest(MemberController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.member.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class MemberControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private MemberService memberService;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(register는 permitAll이라 미사용).
    @MockitoBean private JwtDecoder jwtDecoder;

    /** 모든 필수 필드(약관 동의 포함)를 채운 유효한 가입 본문. 테스트별로 일부를 바꿔 검증한다. */
    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", "new@example.com");
        body.put("password", "P@ssw0rd!");
        body.put("name", "홍길동");
        body.put("nickname", "gildong");
        body.put("nationality", "VN");
        body.put("language", "vi");
        body.put("terms_agreed", true);
        body.put("privacy_agreed", true);
        return body;
    }

    private void expectBadRequest(Map<String, Object> body) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(memberService, never()).signup(any());
    }

    @Test
    @DisplayName("POST /auth/register 201: 약관 동의 포함 정상 가입 → service 호출")
    void register_정상_201() throws Exception {
        given(memberService.signup(any())).willReturn(SignupResponse.builder()
                .publicId("pub-1").email("new@example.com").nickname("gildong").build());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value("pub-1"))
                .andExpect(jsonPath("$.data.nickname").value("gildong"));

        verify(memberService).signup(any());
    }

    @Test
    @DisplayName("POST /auth/register 400: terms_agreed=false(@AssertTrue 위반) → COMMON4001, service 미호출")
    void register_약관_미동의_400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("terms_agreed", false);
        expectBadRequest(body);
    }

    @Test
    @DisplayName("POST /auth/register 400: privacy_agreed=false(@AssertTrue 위반) → COMMON4001, service 미호출")
    void register_개인정보_미동의_400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("privacy_agreed", false);
        expectBadRequest(body);
    }

    @Test
    @DisplayName("(임시) POST /auth/register 201: terms/privacy 미전송(프론트 미연동)이어도 가입 성공 — 백엔드가 동의 처리")
    void register_약관필드_미전송_임시동의_201() throws Exception {
        given(memberService.signup(any())).willReturn(SignupResponse.builder()
                .publicId("pub-1").email("new@example.com").nickname("gildong").build());
        Map<String, Object> body = validBody();
        body.remove("terms_agreed");
        body.remove("privacy_agreed");   // 프론트가 약관 필드를 아예 안 보내는 상황

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        verify(memberService).signup(any());
    }
}
