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
     * §13-4 매핑 표를 그대로 적용한다. {@code BankClientException}이 들고 온
     * 코드/HTTP를 기준으로 본체 도메인 에러를 골라 던질 수 있는 {@link BusinessException}을 만든다.
     */
    static BusinessException toBusinessException(BankClientException ex) {
        String bankCode = ex.getBankCode();
        if (bankCode == null) {
            // 타임아웃·연결 실패 등 코드 없음 → 일시 장애로 본다.
            return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return switch (bankCode) {
            case "BANK4002" -> new BusinessException(AccountErrorCode.INSUFFICIENT_LINKED_ACCOUNT_BALANCE);
            case "BANK4040" -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND);
            case "BANK4003" -> new BusinessException(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED);
            case "BANK4010" -> new BusinessException(AccountErrorCode.UNVERIFIED_ACCOUNT);
            case "BANK4004" -> new BusinessException(CommonErrorCode.INVALID_REQUEST);
            default -> new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        };
    }
}
