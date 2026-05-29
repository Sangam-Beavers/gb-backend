package com.gb.wallet.domain.account.service;

import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;

public interface BankAccountService {

    /** 요청 회원(user_public_id)의 등록된 활성 계좌 목록을 주 계좌 우선, 최신 등록 순으로 조회한다. */
    AccountListResponse getMyAccounts(String userPublicId);

    /**
     * 외부 Mock 은행에 계좌 인증을 위임해 {@code account_token}을 발급받는다.
     * 본체 DB에는 아무것도 쓰지 않는 외부 호출 어댑터.
     */
    VerifyAccountResponse verifyAccount(VerifyAccountRequest request);

    /**
     * 계좌 등록 최종 확정. {@code bank_accounts}에 INSERT하고
     * {@code mock_account_token}을 함께 저장한다(추후 충전 시 사용).
     * 사용자의 첫 활성 계좌면 {@code isPrimary=true}로 강제 등록한다.
     */
    AccountResponse registerAccount(String userPublicId, RegisterAccountRequest request);
}
