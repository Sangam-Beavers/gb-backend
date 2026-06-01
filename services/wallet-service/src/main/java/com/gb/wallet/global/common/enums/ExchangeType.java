package com.gb.wallet.global.common.enums;

/**
 * 환전 유형.
 * <ul>
 *   <li>{@code EXCHANGE} — 원화 → 외화</li>
 *   <li>{@code RE_EXCHANGE} — 외화 → 원화</li>
 * </ul>
 * 방향만 반대고 계산 원리는 같다. enum 값은 SCREAMING_SNAKE_CASE(명세 §14).
 */
public enum ExchangeType {
    EXCHANGE,
    RE_EXCHANGE
}
