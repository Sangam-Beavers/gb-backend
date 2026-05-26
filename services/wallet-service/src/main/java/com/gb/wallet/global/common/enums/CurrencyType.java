package com.gb.wallet.global.common.enums;

/**
 * 지원 통화. 명세상 4종 고정(통화 마스터 테이블 없음).
 * wallet_balances.currency_code (VARCHAR, EnumType.STRING)에 매핑된다.
 */
public enum CurrencyType {
    KRW,
    USD,
    PHP,
    VND
}
