package com.gb.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 각 서비스의 ErrorCode enum(WalletErrorCode 등)이 구현하는 공통 계약.
 * GlobalExceptionHandler는 이 인터페이스 타입만 보고 응답을 조립한다.
 */
public interface ErrorCode {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();
}
