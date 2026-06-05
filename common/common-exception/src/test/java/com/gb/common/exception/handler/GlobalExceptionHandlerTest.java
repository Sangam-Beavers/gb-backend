package com.gb.common.exception.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * {@link GlobalExceptionHandler} 매핑 검증.
 *
 * <p>① DB 무결성 위반(CMN 정합): contextual catch를 거치지 않고 중앙 핸들러까지 올라온
 * {@link DataIntegrityViolationException}은 예상 못한 서버측 결함으로 보고 500 COMMON5000.
 *
 * <p>② HTTP 협상/라우팅 오류(10D common-modules-1): 과거 전용 분기가 없어 catch-all 500 COMMON5000으로
 * 오인되던 404(없는 경로)/405(미허용 메서드)/415(미지원 본문 타입)/406(Accept 협상 실패)이 각각
 * COMMON4041/4051/4151/4061로 응답되는지 확인한다 — 클라이언트 잘못이 서버 오류·거짓 알람으로 보이지 않게.
 * 405/415는 standaloneSetup의 실제 프레임워크 경로로, 404(NoHandlerFound)는 dispatcher 옵션으로,
 * NoResourceFound/406은 컨트롤러 throw로 재현한다(standalone엔 정적 리소스 핸들러가 없어 직접 던져 매핑만 검증).
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addDispatcherServletCustomizer(ds -> ds.setThrowExceptionIfNoHandlerFound(true))
            .build();

    @Test
    @DisplayName("DataIntegrityViolationException(contextual catch 미경유) → 500 COMMON5000(409 '이미 존재' 아님)")
    void dataIntegrityViolation_500_COMMON5000() throws Exception {
        mvc.perform(get("/boom-integrity"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON5000"));
    }

    @Test
    @DisplayName("매핑되지 않은 경로(NoHandlerFound) → 404 COMMON4041(과거 500 COMMON5000 오인 제거)")
    void noHandlerFound_404_COMMON4041() throws Exception {
        mvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4041"));
    }

    @Test
    @DisplayName("정적 리소스 미존재(NoResourceFound, Boot 3.x 기본 경로) → 404 COMMON4041")
    void noResourceFound_404_COMMON4041() throws Exception {
        mvc.perform(get("/boom-no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4041"));
    }

    @Test
    @DisplayName("GET 전용 경로에 POST(메서드 미허용) → 405 COMMON4051")
    void methodNotSupported_405_COMMON4051() throws Exception {
        mvc.perform(post("/get-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4051"));
    }

    @Test
    @DisplayName("consumes=JSON 경로에 text/plain 본문 → 415 COMMON4151")
    void mediaTypeNotSupported_415_COMMON4151() throws Exception {
        mvc.perform(post("/json-only")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain body"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4151"));
    }

    @Test
    @DisplayName("Accept 협상 실패(NotAcceptable) → 406 COMMON4061")
    void mediaTypeNotAcceptable_406_COMMON4061() throws Exception {
        // 요청 Accept는 기본(*/*)으로 둬 에러 envelope(JSON) 직렬화는 성공하는 경로로 매핑만 검증한다.
        // (Accept가 JSON조차 거부하는 극단 케이스는 본문 없이 406 상태만 내려간다 — 핸들러 javadoc 참고.)
        mvc.perform(get("/boom-not-acceptable"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4061"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/boom-integrity")
        String boom() {
            throw new DataIntegrityViolationException("Duplicate entry 'x' for key 'uk_members_email'");
        }

        @GetMapping("/get-only")
        String getOnly() {
            return "ok";
        }

        @PostMapping(value = "/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
        String jsonOnly(@RequestBody String body) {
            return "ok";
        }

        @GetMapping("/boom-no-resource")
        String noResource() throws NoResourceFoundException {
            // standalone 셋업엔 정적 리소스 체인이 없어 실제 발생 경로 재현이 불가 — 타입 매핑만 검증.
            throw new NoResourceFoundException(HttpMethod.GET, "/static/missing.js");
        }

        @GetMapping("/boom-not-acceptable")
        String notAcceptable() throws HttpMediaTypeNotAcceptableException {
            // 협상 실패 재현 대신 타입 매핑 검증(produces 협상 실패는 환경 의존이라 비결정적).
            throw new HttpMediaTypeNotAcceptableException("requested media type not producible");
        }
    }
}
