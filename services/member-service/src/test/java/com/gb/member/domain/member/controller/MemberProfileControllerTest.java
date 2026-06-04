package com.gb.member.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.AuthErrorCode;
import com.gb.common.exception.BusinessException;
import com.gb.common.response.ApiResponse;
import com.gb.member.domain.member.dto.request.SocialProfileRequest;
import com.gb.member.domain.member.dto.response.SocialProfileResponse;
import com.gb.member.domain.member.service.MemberService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link MemberProfileController} 단위 테스트 — 소셜 JIT(completeSocialProfile) 토큰 claim 가드(MEM-06).
 *
 * <p>핵심: 회원 생성에 필요한 claim(public_id/email/name)뿐 아니라 {@code sub}(=authProviderId)도 비면
 * AUTH4011로 막아야 한다. blank sub로 회원이 생성되면 이후 탈퇴 시 {@code deactivateUser(blank)}가
 * COMMON5000으로 실패해 탈퇴 불가가 되는 연쇄를 입구에서 차단한다.
 */
class MemberProfileControllerTest {

    private final MemberService memberService = mock(MemberService.class);
    private final MemberProfileController controller = new MemberProfileController(memberService);

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token").header("alg", "none")
                .claim("public_id", "pub-1")
                .claim("email", "e@example.com")
                .claim("name", "홍길동");
    }

    private SocialProfileRequest socialRequest() {
        SocialProfileRequest request = new SocialProfileRequest();
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        ReflectionTestUtils.setField(request, "nationality", "VN");
        ReflectionTestUtils.setField(request, "language", "vi");
        return request;
    }

    @Test
    @DisplayName("MEM-06: sub(authProviderId) 누락 토큰 → AUTH4011, 서비스 미호출")
    void completeSocialProfile_sub누락_AUTH4011() {
        Jwt jwt = baseJwt().build(); // subject(sub) 미설정 → getSubject() == null

        assertThatThrownBy(() -> controller.completeSocialProfile(jwt, socialRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.UNAUTHORIZED);

        verifyNoInteractions(memberService); // 가드에서 차단 → 회원 생성 미진입
    }

    @Test
    @DisplayName("소셜 JIT 가드 통과: public_id/email/name + sub가 모두 있으면 서비스로 위임(sub→authProviderId 전달)")
    void completeSocialProfile_정상_서비스호출() {
        Jwt jwt = baseJwt().subject("idp-sub-1").build();
        SocialProfileRequest request = socialRequest();
        SocialProfileResponse mockResponse = mock(SocialProfileResponse.class);
        given(memberService.completeSocialProfile(
                eq("pub-1"), eq("e@example.com"), eq("홍길동"), eq("idp-sub-1"), eq(request)))
                .willReturn(mockResponse);

        ApiResponse<SocialProfileResponse> result = controller.completeSocialProfile(jwt, request);

        assertThat(result.getData()).isSameAs(mockResponse);
        verify(memberService).completeSocialProfile(
                eq("pub-1"), eq("e@example.com"), eq("홍길동"), eq("idp-sub-1"), eq(request));
    }
}