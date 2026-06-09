package com.gb.wallet.global.client;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.global.exception.code.AccountErrorCode;

/**
 * Mock 은행 코드({@code BANK####}) → 본체 도메인 에러로 변환.
 * 매핑 표는 API 명세 §13-4를 SSOT로 한다.
 *
 * <p>알 수 없는 코드/네트워크 오류는 {@link CommonErrorCode#SERVICE_UNAVAILABLE}(COMMON5031)로 떨어진다.
 */
final class BankErrorMapper {

    private BankErrorMapper() {}

    /**
     * §13-4 매핑 표를 그대로 적용한다.
     *
     * <ul>
     *   <li>BANK4001 — 요청 값 오류 → COMMON4001</li>
     *   <li>BANK4002 — 잔액 부족 → ACCOUNT4003</li>
     *   <li>BANK4003 — 예금주 불일치 → ACCOUNT4002</li>
     *   <li>BANK4004 — 통화 불일치 → COMMON4001</li>
     *   <li>BANK4005 — 인증번호 불일치 → ACCOUNT4008</li>
     *   <li>BANK4006 — 인증 세션 없음/만료 → ACCOUNT4009</li>
     *   <li>BANK4007 — 이미 사용된 코드 → ACCOUNT4008 (코드 무효와 동일 처리)</li>
     *   <li>BANK4010 — 토큰 무효 → ACCOUNT4006</li>
     *   <li>BANK4040 — 계좌 없음 → ACCOUNT4001</li>
     *   <li>그 외/네트워크 오류 → COMMON5031</li>
     * </ul>
     */
    static BusinessException toBusinessException(BankClientException ex) {
        String bankCode = ex.getBankCode();
        if (bankCode == null) {
            return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, ex);
        }
        return switch (bankCode) {
            case "BANK4001" -> new BusinessException(CommonErrorCode.INVALID_REQUEST, ex);
            case "BANK4002" -> new BusinessException(AccountErrorCode.INSUFFICIENT_LINKED_ACCOUNT_BALANCE, ex);
            case "BANK4003" -> new BusinessException(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED, ex);
            case "BANK4004" -> new BusinessException(CommonErrorCode.INVALID_REQUEST, ex);
            case "BANK4005" -> new BusinessException(AccountErrorCode.VERIFY_CODE_INVALID, ex);
            case "BANK4006" -> new BusinessException(AccountErrorCode.VERIFY_SESSION_NOT_FOUND, ex);
            case "BANK4007" -> new BusinessException(AccountErrorCode.VERIFY_CODE_INVALID, ex);
            case "BANK4010" -> new BusinessException(AccountErrorCode.UNVERIFIED_ACCOUNT, ex);
            case "BANK4040" -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND, ex);
            default -> new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, ex);
        };
    }
}
