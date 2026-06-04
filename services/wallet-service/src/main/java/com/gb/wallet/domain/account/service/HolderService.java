package com.gb.wallet.domain.account.service;

import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;

public interface HolderService {

    /**
     * 외부 Mock 은행에 예금주 실명 조회를 위임한다.
     * 본체 DB에는 쓰지 않는다 — 단순 조회 어댑터.
     * 계좌 없음/통신 실패 등은 {@link com.gb.common.exception.BusinessException}으로 전파된다.
     *
     * <p>예금주 실명은 PII이므로, 무차별 조회(enumeration)를 막기 위해 verify와 동급의 <b>사용자 단위</b>
     * rate-limit을 적용한다(WACC-03). 초과 시 {@code COMMON4291}(429). Redis 장애 시 fail-open.
     *
     * @param userPublicId 인증된 요청자(rate-limit 카운터 키, 위조불가).
     */
    AccountHolderResponse getAccountHolder(String bankCode, String accountNumber, String userPublicId);
}
