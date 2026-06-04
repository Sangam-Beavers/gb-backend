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
 * {@link GlobalExceptionHandler} — DB 무결성 위반 매핑(MEM-02m) 검증.
 *
 * <p>UNIQUE race 등으로 커밋 시점에 던져지는 {@link DataIntegrityViolationException}이 catch-all(→500)이 아니라
 * 409 COMMON4091로 매핑돼 표준 실패 envelope(success:false)로 응답되는지를 MockMvc로 확인한다(같은 advice
 * 안이라 타입 구체성으로 catch-all보다 우선).
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("MEM-02m: DataIntegrityViolationException → 409 COMMON4091(catch-all 500 아님)")
    void dataIntegrityViolation_409_COMMON4091() throws Exception {
        mvc.perform(get("/boom-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4091"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/boom-integrity")
        String boom() {
            throw new DataIntegrityViolationException("Duplicate entry 'x' for key 'uk_members_email'");
        }
    }
}