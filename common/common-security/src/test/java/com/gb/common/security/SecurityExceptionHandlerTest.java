package com.gb.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gb.common.exception.handler.GlobalExceptionHandler;
import com.gb.common.response.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link SecurityExceptionHandler} 검증(CMN-06) — 인가 실패(AccessDeniedException)가 403/COMMON4031로
 * 매핑되는지, 그리고 common-exception {@code GlobalExceptionHandler}의 catch-all(Exception→500)보다
 * 먼저 잡히는지(advice {@code @Order} 우선)를 본다.
 */
class SecurityExceptionHandlerTest {

    private final SecurityExceptionHandler handler = new SecurityExceptionHandler();

    @Test
    @DisplayName("CMN-06: AccessDeniedException → 403 COMMON4031 매핑(단위)")
    void 인가실패_403_COMMON4031_매핑() {
        ResponseEntity<ErrorResponse> res = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().isSuccess()).isFalse();
        assertThat(res.getBody().getCode()).isEqualTo("COMMON4031");
    }

    @Test
    @DisplayName("CMN-06 회귀: catch-all(Exception→COMMON5000/500)보다 우선 — AccessDeniedException은 403으로 매핑")
    void catchall보다_우선해_403() throws Exception {
        // GlobalExceptionHandler를 일부러 먼저(varargs 앞) 등록 — @Order(HIGHEST)가 적용돼야만 보안 advice가
        // 먼저 조회돼 403이 된다. @Order가 무시되면 catch-all이 이겨 500이 떠 테스트가 깨진다(순서 회귀 방지).
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(), new SecurityExceptionHandler())
                .build();

        mvc.perform(get("/boom"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4031"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/boom")
        String boom() {
            throw new AccessDeniedException("nope");
        }
    }
}