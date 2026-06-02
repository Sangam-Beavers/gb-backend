package com.gb.wallet.global.client;

import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;

/**
 * 환율 조회 클라이언트. "1 외화 → KRW" 환율을 제공한다.
 *
 * <p>방식은 충전의 {@code BankClient}와 동일하다 — Service는 이 인터페이스에만 의존하고, 실제 환율
 * 소스(Mock 고정표 / 외부 환율 API)는 구현체를 Profile로 교체한다. 운영 환율 API 도입 시
 * {@code RealExchangeRateClient}만 추가하면 견적/실행 코드는 바뀌지 않는다.
 *
 * <p>회원·환율 조회 API(/wallets/exchange-rates)와도 이 인터페이스를 공유할 수 있다(중복 방지).
 */
public interface ExchangeRateClient {

    /**
     * "1 {@code currency} → KRW" 환율을 반환한다. (예: USD면 1380 = 1달러가 1380원)
     * KRW는 1을 반환한다.
     *
     * @param currency 환율을 구할 통화
     * @return KRW 기준 환율(BigDecimal). 미지원 통화면 호출 측에서 TRANSFER4002로 처리하도록 0 또는 예외 정책을 따른다.
     */
    BigDecimal getRateToKrw(CurrencyType currency);
}
