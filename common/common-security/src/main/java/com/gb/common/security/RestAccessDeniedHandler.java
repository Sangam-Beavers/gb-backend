package com.gb.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.exception.ErrorCode;
import com.gb.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 인가 실패 (인증은 됐으나 권한 없음) 시 보안 <b>필터 체인</b> (AuthorizationFilter) 이 호출하는 "거절 안내문".
 *
 * <p>{@code authorizeHttpRequests(... .hasRole("admin"))} 같은 요청 매처 인가가 실패하면
 * {@code AuthorizationFilter}가 {@link AccessDeniedException}을 던진다. 이 예외는 DispatcherServlet 이전
 * 필터 단계에서 발생하므로 {@code @RestControllerAdvice} ({@link SecurityExceptionHandler}) 가 잡지 못한다 —
 * advice는 컨트롤러·{@code @PreAuthorize} 메서드 보안의 dispatch 단 예외만 본다. 따라서 필터 체인 인가에는
 * {@code SecurityConfig.exceptionHandling().accessDeniedHandler(...)} 에 거는 이 핸들러가 필요하다.
 *
 * <p>응답은 인증 실패 (401/AUTH4011) 를 {@link RestAuthenticationEntryPoint}가 직렬화하는 방식과 동일하게,
 * 표준 실패 포맷 ({@link ErrorResponse}) 의 {@link CommonErrorCode#FORBIDDEN} (COMMON4031, 403) 한 코드로
 * 통일한다 (권한/인증 실패 코드는 새로 만들지 않고 재사용 — CLAUDE.md §6).
 *
 * <p>4개 서비스가 {@code scanBasePackages = "com.gb"}로 스캔하므로 별도 등록 없이 빈으로 잡힌다
 * ({@link RestAuthenticationEntryPoint}와 동일). 필터 체인 인가를 쓰는 SecurityConfig만 이 빈을 주입해
 * {@code accessDeniedHandler}로 건다.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    // Spring이 관리하는 ObjectMapper를 주입받는다 (프로젝트 Jackson 설정 일관 적용, DI 원칙).
    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        ErrorCode errorCode = CommonErrorCode.FORBIDDEN;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ErrorResponse body = ErrorResponse.of(errorCode.getCode(), errorCode.getMessage());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
