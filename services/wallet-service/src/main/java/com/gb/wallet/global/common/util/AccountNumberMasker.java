package com.gb.wallet.global.common.util;

/**
 * 계좌번호 마스킹 유틸 — 전 도메인 공용 SSOT. 응답 직전 표시용으로만 사용한다(WACC-08).
 *
 * <p>규칙(보수적 노출): 앞 3자 + 가운데 별표 + <b>뒤 2자</b>만 노출. 예) {@code "12345678901234"} →
 * {@code "123*********34"}. 길이 5 이하면 전체를 길이만큼 별표로 마스킹(빈 문자열은 {@code ""} 그대로).
 * {@code null}은 그대로 통과한다.
 *
 * <p><b>SSOT로 통일한 이유(WACC-08):</b> 과거 본 유틸(뒤 4자 노출)과 {@code AccountResponse} 내부 마스킹(뒤
 * 2자 노출)이 서로 달라, 같은 계좌가 화면(내 계좌)·송금확인증/최근계좌에서 다른 자릿수로 노출됐다(과다 노출).
 * 더 보수적인 "뒤 2자"로 통일하고, {@code AccountResponse}도 본 유틸에 위임해 규칙을 한 곳으로 모은다.
 */
public final class AccountNumberMasker {

    private AccountNumberMasker() {
        // 인스턴스화 방지.
    }

    public static String mask(String accountNumber) {
        if (accountNumber == null) {
            return null;
        }
        int len = accountNumber.length();
        if (len <= 5) {
            return "*".repeat(len); // 앞3/뒤2가 겹칠 만큼 짧으면 전체 마스킹
        }
        return accountNumber.substring(0, 3)
                + "*".repeat(len - 5)
                + accountNumber.substring(len - 2);
    }
}
