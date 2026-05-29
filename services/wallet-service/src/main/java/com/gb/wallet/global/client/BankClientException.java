package com.gb.wallet.global.client;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Mock 은행(외부 시스템)이 반환한 에러를 어댑터 안에서 표현하는 중간 예외.
 * Mock은 자체 코드({@code BANK####})를 쓰는데, 이 코드와 HTTP 상태를 그대로 보존한 채
 * 어댑터 외부로 던지지 않고 {@link BankErrorMapper}가 본체 도메인의 {@link com.gb.common.exception.BusinessException}으로 변환한다.
 *
 * <p>네트워크 오류(타임아웃·연결 실패)도 이 예외로 표현한다 — 그때는 {@link #bankCode}가
 * {@code null}이거나 합성 코드(예: {@code BANK5000})가 된다.
 */
@Getter
public class BankClientException extends RuntimeException {

    private final String bankCode;
    private final HttpStatus httpStatus;

    public BankClientException(String bankCode, HttpStatus httpStatus, String message) {
        super(message);
        this.bankCode = bankCode;
        this.httpStatus = httpStatus;
    }

    public BankClientException(String bankCode, HttpStatus httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.bankCode = bankCode;
        this.httpStatus = httpStatus;
    }
}
