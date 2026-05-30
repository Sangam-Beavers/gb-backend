package com.gb.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.AuthErrorCode;
import com.gb.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증 실패(토큰 없음·위조·만료) 시 호출되는 검표원의 "거절 안내문".
 *
 * <p>Spring Security는 보호된 엔드포인트에 인증 없이 접근하면 이 EntryPoint를 호출한다. 기본 동작은
 * 비어 있는 401이라 우리 표준 실패 포맷({@link ErrorResponse})과 어긋난다. 그래서 모든 토큰 검증 실패를
 * {@link AuthErrorCode#UNAUTHORIZED}(AUTH4011) 한 코드로 통일해 직렬화한다(상태 노출 방지 — AuthErrorCode 참고).
 *
 * <p>4개 서비스(member/wallet/document/community)가 동일하게 쓰는 공용 컴포넌트라 common-security에 둔다.
 * 사용 서비스는 {@code scanBasePackages = "com.gb"}로 스캔하므로 별도 등록 없이 빈으로 잡힌다.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        AuthErrorCode errorCode = AuthErrorCode.UNAUTHORIZED;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ErrorResponse body = ErrorResponse.of(errorCode.getCode(), errorCode.getMessage());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
