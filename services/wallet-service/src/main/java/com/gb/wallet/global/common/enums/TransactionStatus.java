package com.gb.wallet.global.common.enums;

/**
 * 거래 상태. transactions.status (VARCHAR(20), EnumType.STRING)에 매핑된다. DEFAULT PENDING.
 */
public enum TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
