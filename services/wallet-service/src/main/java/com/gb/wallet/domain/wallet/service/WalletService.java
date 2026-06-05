package com.gb.wallet.domain.wallet.service;

import com.gb.wallet.domain.wallet.dto.response.ExchangeRateWidgetResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletBalanceResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletMeResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletResponse;

public interface WalletService {

    /**
     * 사용자당 1개의 전자지갑을 보장한다(멱등). 이미 존재하면 그대로 반환, 없으면 신규 생성한다.
     *
     * <p>신분증 인증(이슈 #152) APPROVED 시점에 member-service가 호출한다. 신규 생성 시
     * {@code wallets} 1행 + {@code wallet_balances(KRW, 0)} 1행을 같은 트랜잭션에서 작성한다.
     * 동시 호출로 인한 UNIQUE 위반은 catch 후 재조회로 흡수해 멱등성을 지킨다.
     *
     * <p>사용자 식별은 MSA 경계를 넘어가는 {@code user_public_id}(UUID)다(CLAUDE.md §7).
     *
     * @return 생성/조회된 지갑의 단건 응답.
     */
    WalletResponse createOrGetWallet(String userPublicId);

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

    /**
     * 주요 통화의 "1 외화→KRW" 환율 + 전일 대비 등락률(%)을 조회한다 (메인 화면 환율 위젯용).
     *
     * <p>현재 환율은 {@code ExchangeRateClient.getRateToKrw}, 직전(전일) 환율은
     * {@code getPrevRateToKrw} 로 받아 {@code (현재−직전)/직전×100} 으로 등락률을 산정한다.
     * 직전 값이 없으면(첫 실행 / TTL 만료) 등락률 0.
     *
     * @param currencyCodes 콤마 구분 통화 코드(예: {@code "USD,PHP,VND"}). null/공백이면 KRW 제외 전체 지원 통화.
     *                      미지원 코드가 있으면
     *                      {@link com.gb.wallet.global.exception.code.TransferErrorCode#UNSUPPORTED_CURRENCY}.
     */
    ExchangeRateWidgetResponse getExchangeRates(String currencyCodes);
}
