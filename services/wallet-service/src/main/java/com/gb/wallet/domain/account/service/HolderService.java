package com.gb.wallet.domain.account.service;

import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;

public interface HolderService {

    /**
     * 외부 Mock 은행에 예금주 실명 조회를 위임한다.
     * 본체 DB에는 쓰지 않는다 — 단순 조회 어댑터.
     * 계좌 없음/통신 실패 등은 {@link com.gb.common.exception.BusinessException}으로 전파된다.
     */
    AccountHolderResponse getAccountHolder(String bankCode, String accountNumber);
}
