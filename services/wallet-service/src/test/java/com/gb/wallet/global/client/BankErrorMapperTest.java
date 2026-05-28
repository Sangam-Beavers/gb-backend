package com.gb.wallet.global.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@link BankErrorMapper}의 §13-4 매핑 표 검증.
 * 매퍼는 패키지-프라이빗이라 같은 패키지에서 직접 호출한다.
 */
class BankErrorMapperTest {

    @Test
    @DisplayName("BANK4002 → ACCOUNT4003 (연동 계좌 잔액 부족)")
    void map_BANK4002() {
        BusinessException result = BankErrorMapper.toBusinessException(
                new BankClientException("BANK4002", HttpStatus.BAD_REQUEST, "잔액부족"));
        assertThat(result.getErrorCode()).isEqualTo(AccountErrorCode.INSUFFICIENT_LINKED_ACCOUNT_BALANCE);
    }

    @Test
    @DisplayName("BANK4040 → ACCOUNT4001 (계좌 없음)")
    void map_BANK4040() {
        BusinessException result = BankErrorMapper.toBusinessException(
                new BankClientException("BANK4040", HttpStatus.NOT_FOUND, "계좌 없음"));
        assertThat(result.getErrorCode()).isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("BANK4003 → ACCOUNT4002 (예금주 불일치)")
    void map_BANK4003() {
        BusinessException result = BankErrorMapper.toBusinessException(
                new BankClientException("BANK4003", HttpStatus.BAD_REQUEST, "예금주 불일치"));
        assertThat(result.getErrorCode()).isEqualTo(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED);
    }

    @Test
    @DisplayName("BANK4010 → ACCOUNT4006 (유효하지 않은 토큰 = 미인증 계좌)")
    void map_BANK4010() {
        BusinessException result = BankErrorMapper.toBusinessException(
                new BankClientException("BANK4010", HttpStatus.UNAUTHORIZED, "토큰 무효"));
        assertThat(result.getErrorCode()).isEqualTo(AccountErrorCode.UNVERIFIED_ACCOUNT);
    }

    @Test
    @DisplayName("BANK4004 → COMMON4001 (통화 불일치 = 요청 값 오류)")
    void map_BANK4004() {
        BusinessException result = BankErrorMapper.toBusinessException(
                new BankClientException("BANK4004", HttpStatus.BAD_REQUEST, "통화 불일치"));
        assertThat(result.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("BANK5xxx / 알 수 없는 코드 → COMMON5031 (일시 장애)")
    void map_BANK5xxx() {
        BusinessException byBank5000 = BankErrorMapper.toBusinessException(
                new BankClientException("BANK5000", HttpStatus.INTERNAL_SERVER_ERROR, "Mock 내부 오류"));
        BusinessException byUnknown = BankErrorMapper.toBusinessException(
                new BankClientException("BANK9999", HttpStatus.IM_USED, "처음 보는 코드"));

        assertThat(byBank5000.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThat(byUnknown.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("코드 없음(타임아웃·연결 실패) → COMMON5031")
    void map_nullCode_network() {
        BusinessException result = BankErrorMapper.toBusinessException(
                new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE, "타임아웃"));
        assertThat(result.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
