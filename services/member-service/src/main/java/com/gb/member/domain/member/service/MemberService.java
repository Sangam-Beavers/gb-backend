package com.gb.member.domain.member.service;

import com.gb.member.domain.member.dto.request.PasswordResetEmailRequest;
import com.gb.member.domain.member.dto.request.PasswordResetRequest;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.LanguageResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;

public interface MemberService {

    /** 신규 회원을 등록하고 가입 결과(publicId/email/nickname)를 반환한다. */
    SignupResponse signup(SignupRequest request);

    /** 이메일이 사용 가능한지(중복이 아닌지) 확인한다. */
    CheckAvailabilityResponse checkEmail(String email);

    /** 닉네임이 사용 가능한지(중복이 아닌지) 확인한다. */
    CheckAvailabilityResponse checkNickname(String nickname);

    /** 비밀번호 재설정 링크를 이메일로 발송한다(가입된 이메일일 때만 실제 발송, 응답은 항상 동일). */
    void sendPasswordResetEmail(PasswordResetEmailRequest request);

    /** 재설정 토큰을 검증하고 새 비밀번호로 변경한다(IdP 경유). */
    void resetPassword(PasswordResetRequest request);

    /** 현재 회원의 주 사용 언어를 조회한다. 없는(탈퇴 포함) 회원이면 MEMBER4001. */
    LanguageResponse getLanguage(String userPublicId);

    /** 현재 회원의 주 사용 언어를 변경하고 변경된 값을 반환한다. 없는(탈퇴 포함) 회원이면 MEMBER4001. */
    LanguageResponse updateLanguage(String userPublicId, String language);

    /** 현재 회원을 탈퇴 처리한다(로컬 soft delete + IdP 비활성화). 없는(탈퇴 포함) 회원이면 MEMBER4001. */
    void withdraw(String userPublicId);
}
