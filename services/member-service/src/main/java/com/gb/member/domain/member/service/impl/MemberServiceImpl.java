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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

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
        // 이메일 존재 여부와 비밀번호 일치 여부를 별개 코드로 노출하면 가입 이메일 enumeration이
        // 가능하므로, conventions.md §9에 따라 두 케이스 모두 AUTH4001로 통합한다.
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        String accessToken = jwtUtil.generateAccessToken(member.getId(), member.getEmail());
        return LoginResponse.of(accessToken, jwtUtil.getAccessTokenExpirationSeconds());
    }
}
