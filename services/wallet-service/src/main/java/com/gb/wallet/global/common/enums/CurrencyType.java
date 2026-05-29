package com.gb.wallet.global.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 지원 통화. 명세상 4종 고정(통화 마스터 테이블 없음).
 * wallet_balances.currency_code (VARCHAR, EnumType.STRING)에 매핑된다.
 *
 * <p>{@code displayName}/{@code symbol}은 지원 통화 조회 API 응답에 쓰인다 — 통화 마스터 테이블이
 * 없으므로 이 enum이 SSOT.
 */
@Getter
@RequiredArgsConstructor
public enum CurrencyType {

    KRW("Korean Won",       "₩"),
    USD("US Dollar",        "$"),
    PHP("Philippine Peso",  "₱"),
    VND("Vietnamese Dong",  "₫");

    private final String displayName;
    private final String symbol;
}
