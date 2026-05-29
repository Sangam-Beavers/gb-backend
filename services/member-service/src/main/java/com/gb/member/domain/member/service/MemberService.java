package com.gb.member.domain.member.service;

import com.gb.member.domain.member.dto.request.LoginRequest;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.LoginResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;

public interface MemberService {

    /** 신규 회원을 등록하고 가입 결과(memberId/email/nickname)를 반환한다. */
    SignupResponse signup(SignupRequest request);

    /** 이메일/비밀번호로 로그인하고 JWT 액세스 토큰을 발급한다. */
    LoginResponse login(LoginRequest request);
}
