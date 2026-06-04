package com.gb.common.security;

import com.gb.common.exception.CommonErrorCode;
import com.gb.common.exception.ErrorCode;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 인가 실패(권한 없음) 전역 처리기 — Spring Security {@link AccessDeniedException}을 표준 실패
 * 포맷({@link ErrorResponse})의 {@link CommonErrorCode#FORBIDDEN}(COMMON4031, 403)으로 변환한다(CMN-06).
 *
 * <p><b>왜 common-exception이 아니라 common-security에 두나:</b> {@code AccessDeniedException}은 Spring
 * Security 타입이라 common-exception(보안 무의존, CLAUDE.md §2 단방향)에서 다루면 보안 의존이 역류한다.
 * 인증 실패(401/AUTH4011)를 {@link RestAuthenticationEntryPoint}가 common-security에서 처리하는 것과 동일하게
 * 인가 실패(403)도 보안 모듈에서 처리해 결을 맞춘다. 비즈니스/검증 예외는 그대로 common-exception의
 * {@code GlobalExceptionHandler}가 담당한다(에러 코드 SSOT는 {@link CommonErrorCode}로 단일 유지).
 *
 * <p><b>왜 {@link Order#value() HIGHEST_PRECEDENCE}인가:</b> {@code GlobalExceptionHandler}에 catch-all
 * {@code @ExceptionHandler(Exception)}(→COMMON5000/500)이 있어, 여러 advice 해석 시 이 advice가 먼저
 * 조회돼야 {@code AccessDeniedException}이 catch-all(500)로 떨어지지 않고 403으로 매핑된다(advice 단위
 * 우선순위 — 같은 advice 안이면 타입 구체성으로 자동 우선이지만 여기선 다른 advice라 순서가 필요).
 *
 * <p>4개 서비스가 {@code scanBasePackages = "com.gb"}로 스캔하므로 별도 등록 없이 빈으로 잡힌다
 * ({@link RestAuthenticationEntryPoint}와 동일). 현재는 트리거 경로가 없어 선제적 방어이며,
 * 메서드 보안({@code @PreAuthorize}) 등 인가 도입 시 활성화된다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SecurityExceptionHandler {

    /** 인가 실패(권한 없음) → COMMON4031(403). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        ErrorCode errorCode = CommonErrorCode.FORBIDDEN;
        log.warn("Access denied: code={}, detail={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
    }
}