package com.gb.wallet.domain.wallet.service;

import com.gb.wallet.domain.wallet.dto.response.WalletBalanceResponse;

public interface WalletService {

    /** 요청 회원(user_public_id)의 전자지갑 통화별 잔액을 조회한다. */
    WalletBalanceResponse getMyBalances(String userPublicId);
}
