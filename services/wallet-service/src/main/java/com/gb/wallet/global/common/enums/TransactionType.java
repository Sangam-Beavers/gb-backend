package com.gb.wallet.global.common.enums;

import java.util.Optional;

/**
 * 거래 유형. transactions.type (VARCHAR(30), EnumType.STRING)에 매핑된다.
 * 유형별 사용 컬럼은 database.md transactions 표 참고.
 */
public enum TransactionType {
    CHARGE,
    INTERNAL_TRANSFER,
    REMITTANCE,
    EXCHANGE;

    /**
     * 외부 입력 문자열(예: 요청 본문의 {@code transfer_type})을 안전하게 {@link TransactionType}으로
     * 변환한다. 미지원 코드면 {@link Optional#empty()}. enum이 도메인-중립적이도록 throw 대신 Optional을
     * 반환해, 호출 측이 도메인에 맞는 에러(예: {@code TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE})로 매핑한다.
     *
     * <p>{@link CurrencyType#fromCode}와 동일 패턴. 호출 측에서 {@code .filter(...)}로 도메인별 허용
     * 부분집합을 추가로 좁힐 수 있다(예: 송금 수수료 API는 CHARGE/EXCHANGE를 거부).
     */
    public static Optional<TransactionType> fromCode(String code) {
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
