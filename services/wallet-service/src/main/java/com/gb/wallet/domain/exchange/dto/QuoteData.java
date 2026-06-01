package com.gb.wallet.domain.exchange.dto;

import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.ExchangeType;
import java.math.BigDecimal;

/**
 * Redis에 저장하는 견적 스냅샷. 견적 시점에 확정된 환율·수수료·수령액을 담아,
 * 실행 단계에서 그대로 꺼내 쓴다(재계산하지 않는다 — 견적=약속).
 *
 * <p>키 {@code quote:{quotePublicId}}로 TTL 5분 저장된다. TTL이 지나면 Redis가 자동 삭제하므로,
 * 실행 시 조회해서 없으면 "견적 만료"(EXCHANGE4002)로 처리한다.
 *
 * <p>record라 직렬화/역직렬화가 단순하고 불변이다. 회원은 견적 발급자 본인만 실행하도록
 * {@code userPublicId}도 함께 보관한다(다른 사용자가 견적 id를 탈취해 실행하는 것 방지).
 */
public record QuoteData(
        String quotePublicId,
        String userPublicId,
        ExchangeType exchangeType,
        CurrencyType fromCurrencyCode,
        CurrencyType toCurrencyCode,
        BigDecimal amount,
        BigDecimal exchangeRate,
        BigDecimal fee,
        CurrencyType feeCurrencyCode,
        BigDecimal receiveAmount,
        CurrencyType receiveCurrencyCode
) {
}
