package com.gb.common.exception;

import lombok.Getter;

/**
 * 비즈니스 규칙 위반을 표현하는 공통 예외.
 * 각 서비스는 자신의 ErrorCode를 담아 throw하고, GlobalExceptionHandler가 일괄 처리한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
