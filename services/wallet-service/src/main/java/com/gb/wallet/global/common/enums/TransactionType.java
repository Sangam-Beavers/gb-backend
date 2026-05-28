package com.gb.wallet.global.common.enums;

/**
 * 거래 유형. transactions.type (VARCHAR(30), EnumType.STRING)에 매핑된다.
 * 유형별 사용 컬럼은 database.md transactions 표 참고.
 */
public enum TransactionType {
    CHARGE,
    INTERNAL_TRANSFER,
    REMITTANCE,
    EXCHANGE
}
