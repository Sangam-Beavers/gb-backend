package com.gb.common.exception.handler;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.exception.ErrorCode;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
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

    /**
     * {@code @Validated} 컨트롤러의 {@code @RequestParam}/{@code @PathVariable} 검증 실패
     * → COMMON4001. 첫 violation의 메시지를 우선 노출한다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        ErrorCode errorCode = CommonErrorCode.INVALID_REQUEST;
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(errorCode.getMessage());
        log.warn("Constraint violation: code={}, message={}", errorCode.getCode(), message);
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), message));
    }

    /**
     * 필수 요청 파라미터({@code @RequestParam} required) 누락, 필수 헤더({@code @RequestHeader} required)
     * 누락, {@code @PathVariable} 누락 등 Spring의 요청 바인딩 자체가 깨지는 케이스 → COMMON4001.
     *
     * <p>이 분기가 없으면 입력 누락이 fallback {@link Exception} 핸들러로 떨어져 500으로 응답된다 —
     * 사용자 측 잘못인데 서버 오류로 보이게 되므로 400으로 통일한다.
     * {@link MissingServletRequestParameterException}/{@link MissingRequestHeaderException}/
     * {@link MissingPathVariableException} 등은 모두
     * {@link ServletRequestBindingException}의 하위 타입이라 한 핸들러로 묶는다.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> handleBindingException(ServletRequestBindingException e) {
        ErrorCode errorCode = CommonErrorCode.INVALID_REQUEST;
        String message = e.getMessage() != null ? e.getMessage() : errorCode.getMessage();
        log.warn("Request binding failed: code={}, message={}", errorCode.getCode(), message);
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
