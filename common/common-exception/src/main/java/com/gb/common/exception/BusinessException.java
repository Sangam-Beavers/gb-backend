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

    /**
     * 외부 시스템(은행·결제·AI 분석 등) 어댑터에서 본체 도메인 예외로 변환할 때 사용한다.
     * 원본 예외를 cause로 보존해 운영 로그·스택트레이스에서 근본 원인을 추적할 수 있도록 한다.
     */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
