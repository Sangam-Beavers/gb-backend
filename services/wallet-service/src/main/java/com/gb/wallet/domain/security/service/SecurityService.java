package com.gb.wallet.domain.security.service;

import com.gb.wallet.domain.security.dto.response.SecuritySummaryResponse;

/**
 * 전자지갑 보안 점검 서비스. 기존 거래 데이터를 규칙 기반으로 점검해 요약을 만든다(읽기 전용).
 */
public interface SecurityService {

    /** 요청 회원(user_public_id)의 최근 거래를 점검해 보안 요약을 반환한다. */
    SecuritySummaryResponse getSecuritySummary(String userPublicId);
}
