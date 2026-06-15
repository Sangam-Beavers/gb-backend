package com.gb.member.domain.member.service;

import com.gb.member.domain.member.dto.response.CreditResponse;

/**
 * 서류 분석 크레딧 관리 서비스(이슈 #244).
 * document-service가 내부 API({@code /api/v1/internal/members/{id}/credit})를 통해 호출한다.
 */
public interface MemberCreditService {

    /**
     * 회원의 잔여 크레딧을 조회한다.
     *
     * @param userPublicId 회원 public_id
     * @throws com.gb.common.exception.BusinessException MEMBER4001 — 존재하지 않는 회원
     */
    CreditResponse getCredit(String userPublicId);

    /**
     * 크레딧을 1 차감하고 잔여 크레딧을 반환한다.
     * DB UPDATE 레벨에서 원자적으로 처리해 race condition을 방지한다.
     *
     * @param userPublicId 회원 public_id
     * @throws com.gb.common.exception.BusinessException MEMBER4001 — 존재하지 않는 회원
     * @throws com.gb.common.exception.BusinessException MEMBER4007 — 크레딧 부족(잔여 0)
     */
    CreditResponse useCredit(String userPublicId);
}
