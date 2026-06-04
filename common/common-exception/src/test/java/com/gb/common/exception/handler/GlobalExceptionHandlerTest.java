package com.gb.common.exception.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link GlobalExceptionHandler} — DB 무결성 위반 매핑 검증(CMN 정합).
 *
 * <p>예상되는 UNIQUE 중복은 각 서비스가 해당 {@code saveAndFlush} 옆에서 직접 catch해 도메인/COMMON 코드
 * (COMMON4091 등)로 변환하므로, 그 contextual catch를 거치지 않고 중앙 핸들러까지 올라온
 * {@link DataIntegrityViolationException}은 NOT NULL/FK/CHECK 등 예상 못한 서버측 결함으로 보고 500 COMMON5000으로
 * 매핑되는지를 MockMvc로 확인한다("이미 존재"(409)로 오인시키지 않음 — 제약명/SQLState 판별은 비이식적이라 미사용).
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("DataIntegrityViolationException(contextual catch 미경유) → 500 COMMON5000(409 '이미 존재' 아님)")
    void dataIntegrityViolation_500_COMMON5000() throws Exception {
        mvc.perform(get("/boom-integrity"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON5000"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/boom-integrity")
        String boom() {
            throw new DataIntegrityViolationException("Duplicate entry 'x' for key 'uk_members_email'");
        }
    }
}