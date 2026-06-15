package com.gb.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

/**
 * {@link RestAccessDeniedHandler} 검증 — 필터 체인 인가 실패가 403/COMMON4031 표준 실패 포맷으로
 * 직렬화되는지 (인증 실패 401/AUTH4011과 대칭) 본다.
 */
class RestAccessDeniedHandlerTest {

    private final RestAccessDeniedHandler handler = new RestAccessDeniedHandler(new ObjectMapper());

    @Test
    @DisplayName("인가 실패 → 403 + COMMON4031 + success:false (JSON/UTF-8)")
    void 인가실패_403_COMMON4031() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("nope"));

        assertThat(response.getStatus()).isEqualTo(403);
        // MockHttpServletResponse는 charset을 content-type에 덧붙인다 (application/json;charset=UTF-8).
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":\"COMMON4031\"");
        assertThat(response.getContentAsString()).contains("\"success\":false");
    }
}
