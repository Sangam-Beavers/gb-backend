package com.gb.wallet.domain.wallet.service;

import com.gb.wallet.domain.wallet.dto.response.WalletBalanceResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletMeResponse;

public interface WalletService {

    /** 요청 회원(user_public_id)의 전자지갑 통화별 잔액을 조회한다. */
    WalletBalanceResponse getMyBalances(String userPublicId);

    /**
     * 요청 회원(user_public_id)의 전자지갑 상태 + 통화별 잔액 + 원화 환산 + 합산 원화 평가액을 조회한다.
     *
     * <p>각 통화는 {@code ExchangeRateClient.getRateToKrw} 로 "1 외화→KRW" 환율을 받아 잔액에 곱한다.
     * 미지원 통화(환율 null) 발생 시 {@link com.gb.wallet.global.exception.code.TransferErrorCode#UNSUPPORTED_CURRENCY}
     * 로 거부한다.
     */
    WalletMeResponse getMyWallet(String userPublicId);
}
