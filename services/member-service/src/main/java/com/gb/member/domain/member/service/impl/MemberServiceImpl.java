package com.gb.member.domain.member.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.dto.request.LoginRequest;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.LoginResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.exception.code.AuthErrorCode;
import com.gb.member.global.exception.code.MemberErrorCode;
import com.gb.member.global.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Override
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
        }

        if (memberRepository.existsByNickname(request.getNickname())) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());

        Member member = Member.builder()
                .email(request.getEmail())
                .password(encodedPassword)
                .name(request.getName())
                .nickname(request.getNickname())
                .nationality(request.getNationality())
                .language(request.getLanguage())
                .build();

        Member savedMember = memberRepository.save(member);

        return SignupResponse.from(savedMember);
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // Spring Security 표준 흐름: AuthenticationManager → DaoAuthenticationProvider →
        // CustomUserDetailsService(회원 조회) + PasswordEncoder(BCrypt 비교).
        // 이메일 없음(UsernameNotFoundException)과 비밀번호 불일치(BadCredentialsException) 모두
        // AUTH4001로 통일 변환해 enumeration을 방지한다(conventions.md §9).
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (BadCredentialsException | UsernameNotFoundException e) {
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        // 인증 성공 후 memberId가 필요해 한 번 더 조회한다. 인증된 이메일이라 회원은 반드시 존재하지만,
        // 인증과 조회 사이의 동시 탈퇴 같은 극단 케이스에 대비해 동일 코드로 fail-safe 처리.
        String email = authentication.getName();
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_CREDENTIALS));

        String accessToken = jwtUtil.generateAccessToken(member.getId(), member.getEmail());
        return LoginResponse.of(accessToken, jwtUtil.getAccessTokenExpirationSeconds());
    }
}
