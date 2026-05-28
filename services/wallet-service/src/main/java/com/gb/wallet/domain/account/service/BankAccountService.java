package com.gb.wallet.domain.account.service;

import com.gb.wallet.domain.account.dto.response.AccountListResponse;

public interface BankAccountService {

    /** 요청 회원(user_public_id)의 등록된 활성 계좌 목록을 주 계좌 우선, 최신 등록 순으로 조회한다. */
    AccountListResponse getMyAccounts(String userPublicId);
}
