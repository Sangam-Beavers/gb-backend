package com.gb.member.domain.verification.service;

import com.gb.member.domain.verification.dto.request.VerificationRequest;
import com.gb.member.domain.verification.dto.response.VerificationStatusResponse;
import com.gb.member.domain.verification.dto.response.VerificationSubmitResponse;
import com.gb.member.domain.verification.entity.UserVerification;

public interface VerificationService {

    /**
     * 현재 회원의 가장 최근 신분증 인증 상태를 조회한다.
     * 없는(탈퇴 포함) 회원이거나 인증 이력이 없으면 MEMBER4001.
     */
    VerificationStatusResponse getMyVerification(String userPublicId);

    /**
     * 신분증 인증을 요청한다. 유형별 번호 형식(정규식) 검증을 통과하면 즉시 승인하고
     * 회원에 인증 배지(is_verified=true)를 부여한다(데모). 승인 커밋 후 지갑 자동 개설을
     * 위임한다(이슈 #152, fail-open — 호출 실패가 인증 결과를 바꾸지 않음).
     * 형식 불일치 → COMMON4001, 이미 진행중/승인 인증 존재 → COMMON4091, 없는 회원 → MEMBER4001.
     */
    VerificationSubmitResponse submitVerification(String userPublicId, VerificationRequest request);

    /**
     * 신분증 인증의 DB 본문(중복 검사 → 형식 검증 → 저장 → 배지 부여)만 트랜잭션으로 처리한다.
     *
     * <p><b>self-proxy 전용</b> — {@link #submitVerification}이 프록시를 통해 호출해야 {@code @Transactional}이
     * 적용된다(같은 빈 내부 직접 호출은 AOP 우회). 지갑 자동 개설(WalletClient, 외부 HTTP)은 이 트랜잭션
     * <b>밖</b>(submitVerification)에서 한다 — 쓰기 tx·커넥션을 보유한 채 HTTP 응답을 기다리지 않기 위함
     * (community createCommentTx·wallet registerAccountLocked와 동일 구조). 다른 컴포넌트에서 직접 호출하지 말 것.
     */
    UserVerification submitVerificationTx(String userPublicId, VerificationRequest request);
}
