package com.gb.member.domain.member.service;

import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;

public interface MemberService {

    /** 신규 회원을 등록하고 가입 결과(publicId/email/nickname)를 반환한다. */
    SignupResponse signup(SignupRequest request);

    /** 이메일이 사용 가능한지(중복이 아닌지) 확인한다. */
    CheckAvailabilityResponse checkEmail(String email);

    /** 닉네임이 사용 가능한지(중복이 아닌지) 확인한다. */
    CheckAvailabilityResponse checkNickname(String nickname);
}
