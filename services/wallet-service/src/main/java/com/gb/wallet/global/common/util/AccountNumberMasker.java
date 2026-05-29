package com.gb.wallet.global.common.util;

/**
 * 계좌번호 마스킹 유틸. 응답 직전 표시용으로만 사용한다.
 *
 * <p>규칙: 앞 3자 + {@code "-****-"} + 뒤 4자. 예) {@code "1234567891111"} → {@code "123-****-1111"}.
 * 길이 7 이하면 전체를 {@code "****"}로 마스킹(짧은 입력 안전 fallback).
 * {@code null}/빈 문자열은 그대로 통과시켜 호출 측이 결정하게 한다.
 *
 * <p>이미 도메인에는 다른 형식의 마스킹(예: {@code com.gb.wallet.domain.account.dto.response.AccountResponse}
 * 내부의 {@code 123*********34})이 있다. 두 규칙의 의도가 달라(자기 계좌 조회 vs 최근 송금 계좌 조회 명세)
 * 통합 대신 별개 유틸로 둔다.
 */
public final class AccountNumberMasker {

    private AccountNumberMasker() {
        // 인스턴스화 방지.
    }

    public static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.isEmpty()) {
            return accountNumber;
        }
        if (accountNumber.length() <= 7) {
            return "****";
        }
        return accountNumber.substring(0, 3)
                + "-****-"
                + accountNumber.substring(accountNumber.length() - 4);
    }
}
