package com.gb.member.domain.verification.service;

import com.gb.member.domain.verification.dto.request.VerificationRequest;
import com.gb.member.domain.verification.dto.response.VerificationStatusResponse;
import com.gb.member.domain.verification.dto.response.VerificationSubmitResponse;

public interface VerificationService {

    /**
     * 현재 회원의 가장 최근 신분증 인증 상태를 조회한다.
     * 없는(탈퇴 포함) 회원이거나 인증 이력이 없으면 MEMBER4001.
     */
    VerificationStatusResponse getMyVerification(String userPublicId);

    /**
     * 신분증 인증을 요청한다. 유형별 번호 형식(정규식) 검증을 통과하면 즉시 승인하고
     * 회원에 인증 배지(is_verified=true)를 부여한다(데모).
     * 형식 불일치 → COMMON4001, 이미 진행중/승인 인증 존재 → COMMON4091, 없는 회원 → MEMBER4001.
     */
    VerificationSubmitResponse submitVerification(String userPublicId, VerificationRequest request);
}
