package com.gb.common.exception.handler;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.exception.ErrorCode;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 모든 서비스에 공통 적용되는 전역 예외 처리기.
 * 실패 응답은 항상 {@link ApiResponse#fail(String, String)}({@link ErrorResponse}) 포맷으로 통일한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 비즈니스 예외 → ErrorCode가 지정한 HttpStatus로 응답. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("BusinessException: code={}, message={}", errorCode.getCode(), errorCode.getMessage());
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /** @Valid 검증 실패 → COMMON4001. 첫 필드 에러 메시지를 우선 노출한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        ErrorCode errorCode = CommonErrorCode.INVALID_REQUEST;
        String message = resolveValidationMessage(e, errorCode);
        log.warn("Validation failed: code={}, message={}", errorCode.getCode(), message);
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), message));
    }

    /** 처리되지 않은 모든 예외 → COMMON5000. 스택트레이스 포함 error 레벨 로깅. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        ErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
        log.error("Unhandled exception", e);
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    private String resolveValidationMessage(MethodArgumentNotValidException e, ErrorCode fallback) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError != null && fieldError.getDefaultMessage() != null) {
            return fieldError.getDefaultMessage();
        }
        return fallback.getMessage();
    }
}
