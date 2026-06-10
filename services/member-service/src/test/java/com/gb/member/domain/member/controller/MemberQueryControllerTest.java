package com.gb.member.domain.member.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gb.common.exception.BusinessException;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.MemberDisplayListResponse;
import com.gb.member.domain.member.dto.response.MemberDisplayResponse;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.config.WebConfig;
import com.gb.member.global.exception.code.MemberErrorCode;
import com.gb.member.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * {@link MemberQueryController}의 HTTP wiring 검증 — 신규 표시정보 조회(display-info/by-email)의
 * URL/검증(@Validated + @RequestParam 제약), 인증 보호(authenticated — permitAll 아님), snake_case
 * 직렬화(public_id/is_verified — Boolean boxed 함정), 에러 변환(COMMON4001/MEMBER4001/AUTH4011)까지.
 *
 * <p>슬라이스 구성 사유는 {@link MemberMeControllerTest} 클래스 javadoc 참고(동일 구성):
 * GlobalExceptionHandler·SecurityConfig·entry point·WebConfig·ArgumentResolver를 명시 @Import.
 */
@WebMvcTest(MemberQueryController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.member.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class MemberQueryControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MemberService memberService;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean private JwtDecoder jwtDecoder;

    /** 인증된 요청용 JWT 주입. display-info/by-email은 식별자 값을 쓰지 않아 claim 내용은 무관(인증 여부만). */
    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", "caller-uuid"));
    }

    private static MemberDisplayResponse displayResponse(String publicId, String name,
                                                         String nickname, String nationality,
                                                         boolean isVerified) {
        return MemberDisplayResponse.builder()
                .publicId(publicId)
                .name(name)
                .nickname(nickname)
                .nationality(nationality)
                .isVerified(isVerified)
                // 이슈 #193 Phase 1 산정 규칙과 동일하게 채운다(인증=VERIFIED, 미인증=NEWCOMER).
                .trustGrade(isVerified ? "VERIFIED" : "NEWCOMER")
                .build();
    }

    // --- GET /display-info ---

    @Test
    @DisplayName("GET /display-info 200: 콤마 구분 public_ids가 List로 바인딩되고 snake_case(public_id/is_verified)로 직렬화된다")
    void displayInfo_정상_배치조회() throws Exception {
        given(memberService.getDisplayInfos(List.of("pub-1", "pub-2")))
                .willReturn(MemberDisplayListResponse.of(List.of(
                        displayResponse("pub-1", "Nguyen Thi Linh", "Linh", "VN", true),
                        displayResponse("pub-2", "Maria Santos", "Maria", "PH", false))));

        mockMvc.perform(get("/api/v1/members/display-info")
                        .param("public_ids", "pub-1,pub-2")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.members.length()").value(2))
                .andExpect(jsonPath("$.data.members[0].public_id").value("pub-1"))
                .andExpect(jsonPath("$.data.members[0].name").value("Nguyen Thi Linh"))
                .andExpect(jsonPath("$.data.members[0].nickname").value("Linh"))
                .andExpect(jsonPath("$.data.members[0].nationality").value("VN"))
                // Boolean boxed 직렬화 검증 — primitive면 'verified'로 떨어지는 함정(ProfileResponse 주석 참고).
                .andExpect(jsonPath("$.data.members[0].is_verified").value(true))
                .andExpect(jsonPath("$.data.members[1].is_verified").value(false))
                // 이슈 #193 — 신뢰등급 snake_case(trust_grade) 직렬화 검증.
                .andExpect(jsonPath("$.data.members[0].trust_grade").value("VERIFIED"))
                .andExpect(jsonPath("$.data.members[1].trust_grade").value("NEWCOMER"));

        verify(memberService).getDisplayInfos(List.of("pub-1", "pub-2"));
    }

    @Test
    @DisplayName("GET /display-info 400: public_ids가 비면 @NotEmpty 위반 → COMMON4001, service 미호출")
    void displayInfo_빈_publicIds_400() throws Exception {
        mockMvc.perform(get("/api/v1/members/display-info")
                        .param("public_ids", "")
                        .with(authedJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(memberService, never()).getDisplayInfos(anyList());
    }

    @Test
    @DisplayName("GET /display-info 400: public_ids가 100개를 초과하면 @Size 위반 → COMMON4001(IN 절 폭주 가드)")
    void displayInfo_상한_초과_400() throws Exception {
        String tooMany = IntStream.rangeClosed(1, 101)
                .mapToObj(i -> "pub-" + i)
                .collect(Collectors.joining(","));

        mockMvc.perform(get("/api/v1/members/display-info")
                        .param("public_ids", tooMany)
                        .with(authedJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(memberService, never()).getDisplayInfos(anyList());
    }

    @Test
    @DisplayName("GET /display-info 401: 토큰 없이 호출하면 AUTH4011 — permitAll이 아닌 인증 보호 경로")
    void displayInfo_미인증_401() throws Exception {
        mockMvc.perform(get("/api/v1/members/display-info")
                        .param("public_ids", "pub-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verify(memberService, never()).getDisplayInfos(anyList());
    }

    // --- GET /by-email ---

    @Test
    @DisplayName("GET /by-email 200: 활성 회원이면 표시정보 단건 반환(snake_case 직렬화)")
    void byEmail_정상() throws Exception {
        given(memberService.getDisplayInfoByEmail("linh@example.com"))
                .willReturn(displayResponse("pub-1", "Nguyen Thi Linh", "Linh", "VN", true));

        mockMvc.perform(get("/api/v1/members/by-email")
                        .param("email", "linh@example.com")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value("pub-1"))
                .andExpect(jsonPath("$.data.nickname").value("Linh"))
                .andExpect(jsonPath("$.data.is_verified").value(true));

        verify(memberService).getDisplayInfoByEmail("linh@example.com");
    }

    @Test
    @DisplayName("GET /by-email 400: 이메일 형식 위반(@Email) → COMMON4001, service 미호출")
    void byEmail_형식위반_400() throws Exception {
        mockMvc.perform(get("/api/v1/members/by-email")
                        .param("email", "not-an-email")
                        .with(authedJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(memberService, never()).getDisplayInfoByEmail(anyString());
    }

    @Test
    @DisplayName("GET /by-email 404: service가 MEMBER4001(미존재·탈퇴) 던지면 → 404 + code(fail-fast 검증 경로)")
    void byEmail_미존재_404() throws Exception {
        willThrow(new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND))
                .given(memberService).getDisplayInfoByEmail(anyString());

        mockMvc.perform(get("/api/v1/members/by-email")
                        .param("email", "ghost@example.com")
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MEMBER4001"));
    }

    @Test
    @DisplayName("GET /by-email 401: 토큰 없이 호출하면 AUTH4011 — 인증 보호 경로")
    void byEmail_미인증_401() throws Exception {
        mockMvc.perform(get("/api/v1/members/by-email")
                        .param("email", "linh@example.com"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verify(memberService, never()).getDisplayInfoByEmail(anyString());
    }

    // --- 기존 공개 경로 회귀 가드 ---

    @Test
    @DisplayName("GET /check-email 200: 신규 인증 경로 추가 후에도 가입 전 공개 조회(permitAll)는 토큰 없이 동작한다")
    void checkEmail_공개경로_회귀가드() throws Exception {
        given(memberService.checkEmail("free@example.com"))
                .willReturn(CheckAvailabilityResponse.of(true));

        mockMvc.perform(get("/api/v1/members/check-email")
                        .param("email", "free@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
    }
}
