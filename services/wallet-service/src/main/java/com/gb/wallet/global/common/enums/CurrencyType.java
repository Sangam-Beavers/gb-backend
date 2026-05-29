package com.gb.wallet.global.common.enums;

import java.util.Optional;
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

    /**
     * 외부 입력 문자열(예: 요청 본문의 {@code currency_code})을 안전하게 {@link CurrencyType}으로 변환한다.
     * 미지원 코드면 {@link Optional#empty()}. enum이 도메인-중립적이도록 throw 대신 Optional을 반환해,
     * 호출 측이 도메인에 맞는 에러(예: {@code TransferErrorCode.UNSUPPORTED_CURRENCY})로 매핑한다.
     */
    public static Optional<CurrencyType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
