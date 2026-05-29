package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.dto.request.LoginRequest;
import com.gb.member.domain.member.dto.response.LoginResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.global.exception.code.AuthErrorCode;
import com.gb.member.global.jwt.JwtUtil;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Spring Security 표준 인증 흐름으로 전환된 {@code login()}에 대한 단위 테스트.
 *
 * <p>실제 {@code AuthenticationManager}는 mock하고, 성공/실패 경로마다 우리 서비스가
 * 약속한 응답(LoginResponse)과 에러(AUTH4001)를 만들어내는지만 검증한다. BCrypt 검증·DB 조회 등
 * 의 실동작은 통합 테스트 영역.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceImplTest {

    @Mock private MemberRepository memberRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthenticationManager authenticationManager;

    @InjectMocks private MemberServiceImpl memberService;

    private LoginRequest request;

    @BeforeEach
    void setUp() {
        request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", "user@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
    }

    @Test
    @DisplayName("인증 성공 시 JWT 액세스 토큰을 담은 LoginResponse를 반환한다")
    void login_성공() {
        // given
        Member member = Member.builder()
                .email("user@example.com")
                .password("encoded")
                .name("홍길동")
                .nickname("gildong")
                .nationality("KR")
                .language("ko")
                .build();
        ReflectionTestUtils.setField(member, "id", 42L);

        Authentication authResult = new UsernamePasswordAuthenticationToken(
                "user@example.com", null, Collections.emptyList());
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authResult);
        when(memberRepository.findByEmail("user@example.com")).thenReturn(Optional.of(member));
        when(jwtUtil.generateAccessToken(42L, "user@example.com")).thenReturn("issued.jwt.token");
        when(jwtUtil.getAccessTokenExpirationSeconds()).thenReturn(3600L);

        // when
        LoginResponse response = memberService.login(request);

        // then
        assertThat(response.getAccessToken()).isEqualTo("issued.jwt.token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("비밀번호 불일치(BadCredentialsException)는 AUTH4001로 통일 변환된다")
    void login_비밀번호불일치_AUTH4001() {
        // given
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("bad credentials"));

        // when / then
        assertThatThrownBy(() -> memberService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

        // 인증 실패 시 토큰 발급/추가 조회는 일어나지 않아야 한다.
        verify(memberRepository, never()).findByEmail(any());
        verify(jwtUtil, never()).generateAccessToken(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 이메일(UsernameNotFoundException)도 AUTH4001로 통일 변환된다")
    void login_이메일없음_AUTH4001() {
        // given — enumeration 방지: 비밀번호 불일치와 동일한 응답
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new UsernameNotFoundException("not found"));

        // when / then
        assertThatThrownBy(() -> memberService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

        verify(memberRepository, never()).findByEmail(any());
        verify(jwtUtil, never()).generateAccessToken(any(), any());
    }
}
