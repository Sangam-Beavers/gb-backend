package com.gb.common.exception.handler;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.exception.ErrorCode;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 모든 서비스에 공통 적용되는 전역 예외 처리기.
 * 실패 응답은 항상 {@link ApiResponse#fail(String, String)}({@link ErrorResponse}) 포맷으로 통일한다.
 *
 * <p><b>보안 예외는 여기서 다루지 않는다(common 의존 방향 유지, CLAUDE.md §2):</b> 인증 실패(401/AUTH4011)는
 * common-security의 {@code RestAuthenticationEntryPoint}가, 인가 실패(403/COMMON4031)는 common-security의
 * {@code SecurityExceptionHandler}(catch-all보다 먼저 잡히도록 {@code @Order} 우선)가 처리한다. 둘 다 Spring
 * Security 타입이라 보안 모듈에 둬, 보안 무의존인 common-exception으로 의존이 역류하지 않게 한다. 에러 코드
 * 자체의 SSOT는 {@code CommonErrorCode}로 단일 유지된다.
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
        // 프레임워크 raw 메시지(예: "Required request header 'X-...' is not present")는 내부 헤더/파라미터명을
        // 노출할 수 있어 응답엔 고정 메시지만 싣고, 진단용 상세는 서버 로그로만 남긴다.
        log.warn("Request binding failed: code={}, detail={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /**
     * 본문 파싱 실패({@link HttpMessageNotReadableException} — 잘못된/빈 JSON·타입 불일치 본문)와
     * {@code @RequestParam}/{@code @PathVariable} 타입 변환 실패({@link MethodArgumentTypeMismatchException})
     * → COMMON4001(400). 둘 다 클라이언트 입력 문제라 fallback 500이 아닌 400으로 통일한다.
     * 프레임워크 raw 메시지는 내부 구조를 노출할 수 있어 응답엔 고정 메시지만, 상세는 서버 로그로만 남긴다.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleNotReadableOrTypeMismatch(Exception e) {
        ErrorCode errorCode = CommonErrorCode.INVALID_REQUEST;
        log.warn("Malformed request body/param: code={}, detail={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /**
     * DB 무결성 제약 위반({@link DataIntegrityViolationException}) → 500 COMMON5000(서버측 결함, CMN 정합).
     *
     * <p><b>왜 500인가:</b> 예상되는 UNIQUE 중복(동시 가입/생성 race 등)은 각 서비스가 해당 {@code saveAndFlush}
     * 바로 옆에서 이 예외를 직접 catch해 도메인/COMMON 코드(COMMON4091 "이미 존재", ACCOUNT4004 등)로 변환한다
     * (LikeServiceImpl·BankAccountServiceImpl·MemberServiceImpl). 그 contextual catch를 거치지 않고 여기까지
     * 올라온 DataIntegrityViolation은 NOT NULL/FK/CHECK 위반 등 <b>예상치 못한 서버측 결함</b>일 가능성이 높아
     * "이미 존재"(409)가 아니라 500으로 처리한다(서버 오류를 클라이언트 충돌로 오인시키지 않음). 제약명/SQLState로
     * UNIQUE만 골라내는 판별은 DB별로 값이 달라(MySQL 23000 vs H2 23505, {@code getConstraintName} null 가능)
     * 비이식적이라 쓰지 않고, 기대 중복은 호출부에서 contextual하게 잡는 것을 표준으로 한다. 진단 상세는 응답에
     * 노출하지 않고 서버 로그(error)로만 남긴다(내부 구조 비노출).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        ErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
        // 기대 중복은 호출부 contextual catch에서 처리됐어야 한다 — 여기 도달 = 예상 못한 무결성 결함이라 error 로깅.
        log.error("Unexpected data integrity violation (expected duplicates are caught contextually): detail={}",
                e.getMessage(), e);
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
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
