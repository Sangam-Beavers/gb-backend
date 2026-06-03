package com.gb.wallet.domain.exchange.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.wallet.domain.exchange.dto.response.ExchangeListResponse;
import com.gb.wallet.domain.exchange.dto.response.ExchangeResponse;
import com.gb.wallet.domain.exchange.dto.response.QuoteResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.exchange.service.ExchangeService;
import com.gb.wallet.global.config.WebConfig;
import com.gb.wallet.global.exception.code.ExchangeErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link ExchangeController}의 HTTP wiring 검증 — URL/메서드, @Valid·@Validated(@Min/@Max),
 * @RequestHeader(Idempotency-Key), @ResponseStatus(201), ApiResponse 래핑·snake_case 직렬화,
 * 인증(AUTH4011)·BusinessException → ErrorResponse 변환까지. Service 로직은
 * {@link com.gb.wallet.domain.exchange.service.impl.ExchangeServiceImplTest}가 담당 — 여기는 어댑터 경계만.
 *
 * <p>{@code GlobalExceptionHandler}(common-exception)와 {@code SecurityConfig}는 @WebMvcTest 기본 스캔에
 * 안 잡히므로 명시 @Import한다(운영과 동일한 permitAll + 공용 예외 처리기 경로) — AccountControllerTest와 동일 패턴.
 */
@WebMvcTest(ExchangeController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.wallet.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class ExchangeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ExchangeService exchangeService;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final String USER_ID = "test-uuid-1234";
    private static final String KEY = "idem-1";

    /** 인증된 요청용 JWT 주입(public_id claim = USER_ID). 컨트롤러는 이 claim으로 사용자를 식별한다. */
    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER_ID));
    }

    /** 토큰은 유효하나 public_id claim이 없는 JWT(IdP Property Mapping 누락) — resolver fail-fast 검증용. */
    private static RequestPostProcessor jwtWithoutPublicId() {
        return jwt().jwt(j -> j.claim("sub", "no-mapping"));
    }

    // ───────────────────── GET /supported-currencies ─────────────────────

    @Test
    @DisplayName("GET /supported-currencies 200: 인증 시 data.currencies 배열 반환")
    void supportedCurrencies_정상() throws Exception {
        given(exchangeService.getSupportedCurrencies()).willReturn(SupportedCurrenciesResponse.of());

        mockMvc.perform(get("/api/v1/exchanges/supported-currencies").with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.currencies").isArray());
    }

    @Test
    @DisplayName("GET /supported-currencies 401: 토큰 없음 → AUTH4011, service 미호출")
    void supportedCurrencies_토큰없음_401() throws Exception {
        mockMvc.perform(get("/api/v1/exchanges/supported-currencies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(exchangeService);
    }

    // ───────────────────── POST /quote ─────────────────────

    @Test
    @DisplayName("POST /quote 200: 정상 시 ApiResponse + 견적(snake_case) 반환")
    void quote_정상() throws Exception {
        given(exchangeService.createQuote(eq(USER_ID), any())).willReturn(stubQuote());

        mockMvc.perform(post("/api/v1/exchanges/quote")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "exchange_type", "EXCHANGE",
                                "from_currency_code", "KRW",
                                "to_currency_code", "USD",
                                "amount", "100000.0000"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.quote_public_id").value("quote-1"))
                .andExpect(jsonPath("$.data.receive_amount").value("72.1014"))
                .andExpect(jsonPath("$.data.fee_currency_code").value("KRW"));

        verify(exchangeService).createQuote(eq(USER_ID), any());
    }

    @Test
    @DisplayName("POST /quote 400: 필수값 누락(amount) → COMMON4001, service 미호출")
    void quote_필수값_누락() throws Exception {
        mockMvc.perform(post("/api/v1/exchanges/quote")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "exchange_type", "EXCHANGE",
                                "from_currency_code", "KRW",
                                "to_currency_code", "USD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(exchangeService);
    }

    @Test
    @DisplayName("POST /quote 401: 토큰 없음 → AUTH4011, service 미호출")
    void quote_토큰없음_401() throws Exception {
        mockMvc.perform(post("/api/v1/exchanges/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "exchange_type", "EXCHANGE",
                                "from_currency_code", "KRW",
                                "to_currency_code", "USD",
                                "amount", "100000.0000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(exchangeService);
    }

    // ───────────────────── POST /exchanges (실행) ─────────────────────

    @Test
    @DisplayName("POST /exchanges 201: 정상 실행 → 201 + ExchangeResponse(snake_case), service 호출")
    void execute_정상_201() throws Exception {
        given(exchangeService.execute(eq(USER_ID), eq(KEY), any())).willReturn(stubExchange());

        mockMvc.perform(post("/api/v1/exchanges")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quote_public_id", "quote-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value("ex-1"))
                .andExpect(jsonPath("$.data.exchange_type").value("EXCHANGE"))
                .andExpect(jsonPath("$.data.receive_amount").value("72.1014"))
                .andExpect(jsonPath("$.data.exchanged_at").value("2026-05-26T05:30:00Z"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(exchangeService).execute(eq(USER_ID), eq(KEY), any());
    }

    @Test
    @DisplayName("POST /exchanges 400: Idempotency-Key 헤더 누락 → COMMON4001, service 미호출")
    void execute_멱등헤더_누락() throws Exception {
        mockMvc.perform(post("/api/v1/exchanges")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quote_public_id", "quote-1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(exchangeService);
    }

    @Test
    @DisplayName("POST /exchanges 400: quote_public_id 누락(@NotBlank) → COMMON4001, service 미호출")
    void execute_견적식별자_누락() throws Exception {
        mockMvc.perform(post("/api/v1/exchanges")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(exchangeService);
    }

    @Test
    @DisplayName("POST /exchanges 401: 토큰 없음 → AUTH4011, service 미호출")
    void execute_토큰없음_401() throws Exception {
        mockMvc.perform(post("/api/v1/exchanges")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quote_public_id", "quote-1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(exchangeService);
    }

    @Test
    @DisplayName("POST /exchanges 401: 만료/위조 토큰(JwtDecoder.decode→BadJwtException) → AUTH4011, service 미호출")
    void execute_만료토큰_401() throws Exception {
        // 실제 Authorization 헤더로 보내 BearerTokenAuthenticationFilter가 decode를 타게 한다
        // (jwt() 후처리기는 필터를 우회). BadJwtException → InvalidBearerTokenException(401) → entry point.
        given(jwtDecoder.decode(anyString()))
                .willThrow(new BadJwtException("Jwt expired at 2026-06-02T02:27:55Z"));

        mockMvc.perform(post("/api/v1/exchanges")
                        .header("Authorization", "Bearer expired.jwt.token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quote_public_id", "quote-1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(exchangeService);
    }

    @Test
    @DisplayName("POST /exchanges 401: 토큰은 유효하나 public_id claim 누락 → AUTH4011(resolver fail-fast), service 미호출")
    void execute_publicId_누락_401() throws Exception {
        mockMvc.perform(post("/api/v1/exchanges")
                        .with(jwtWithoutPublicId())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quote_public_id", "quote-1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(exchangeService);
    }

    @Test
    @DisplayName("POST /exchanges 422: service가 WALLET4002(잔액 부족) 던지면 → 422 + code (EX-D1: 422 SSOT)")
    void execute_잔액부족_422() throws Exception {
        willThrow(new BusinessException(WalletErrorCode.INSUFFICIENT_BALANCE))
                .given(exchangeService).execute(anyString(), anyString(), any());

        mockMvc.perform(post("/api/v1/exchanges")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quote_public_id", "quote-1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WALLET4002"));
    }

    @Test
    @DisplayName("POST /exchanges 404: service가 EXCHANGE4001(타인/타유형 멱등키) 던지면 → 404 + code (EX-FIX)")
    void execute_멱등키_교차_404() throws Exception {
        willThrow(new BusinessException(ExchangeErrorCode.EXCHANGE_NOT_FOUND))
                .given(exchangeService).execute(anyString(), anyString(), any());

        mockMvc.perform(post("/api/v1/exchanges")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quote_public_id", "quote-1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXCHANGE4001"));
    }

    // ───────────────────── GET /exchanges/{id} (단건) ─────────────────────

    @Test
    @DisplayName("GET /exchanges/{id} 200: 정상 시 ExchangeResponse 반환")
    void getExchange_정상() throws Exception {
        given(exchangeService.getExchange(eq(USER_ID), eq("ex-1"))).willReturn(stubExchange());

        mockMvc.perform(get("/api/v1/exchanges/{id}", "ex-1").with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value("ex-1"));
    }

    @Test
    @DisplayName("GET /exchanges/{id} 404: service가 EXCHANGE4001 던지면 → 404 + code")
    void getExchange_없음_404() throws Exception {
        willThrow(new BusinessException(ExchangeErrorCode.EXCHANGE_NOT_FOUND))
                .given(exchangeService).getExchange(anyString(), anyString());

        mockMvc.perform(get("/api/v1/exchanges/{id}", "nope").with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXCHANGE4001"));
    }

    @Test
    @DisplayName("GET /exchanges/{id} 403: service가 COMMON4031(타인) 던지면 → 403 + code")
    void getExchange_타인_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .given(exchangeService).getExchange(anyString(), anyString());

        mockMvc.perform(get("/api/v1/exchanges/{id}", "ex-9").with(authedJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON4031"));
    }

    // ───────────────────── GET /exchanges (목록) ─────────────────────

    @Test
    @DisplayName("GET /exchanges 200: data.exchanges 배열 + 페이지 메타(snake_case)")
    void getExchanges_정상() throws Exception {
        given(exchangeService.getExchanges(eq(USER_ID), eq(0), eq(20)))
                .willReturn(ExchangeListResponse.of(List.of(stubExchange()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/exchanges")
                        .with(authedJwt())
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.exchanges").isArray())
                .andExpect(jsonPath("$.data.exchanges[0].public_id").value("ex-1"))
                .andExpect(jsonPath("$.data.total_elements").value(1))
                .andExpect(jsonPath("$.data.total_pages").value(1));
    }

    @Test
    @DisplayName("GET /exchanges 400: page/size 범위 위반(@Min/@Max) → COMMON4001, service 미호출")
    void getExchanges_페이지_범위위반() throws Exception {
        // page<0, size<1, size>100 모두 ConstraintViolationException → COMMON4001
        for (Map<String, String> bad : List.of(
                Map.of("page", "-1", "size", "20"),
                Map.of("page", "0", "size", "0"),
                Map.of("page", "0", "size", "101"))) {
            mockMvc.perform(get("/api/v1/exchanges")
                            .with(authedJwt())
                            .param("page", bad.get("page"))
                            .param("size", bad.get("size")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON4001"));
        }

        verifyNoInteractions(exchangeService);
    }

    @Test
    @DisplayName("GET /exchanges 401: 토큰 없음 → AUTH4011, service 미호출")
    void getExchanges_토큰없음_401() throws Exception {
        mockMvc.perform(get("/api/v1/exchanges"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(exchangeService);
    }

    // ----- stubs -----

    private static QuoteResponse stubQuote() {
        return QuoteResponse.builder()
                .quotePublicId("quote-1")
                .exchangeRate("1380.0000")
                .fee("500.0000")
                .feeCurrencyCode("KRW")
                .receiveAmount("72.1014")
                .receiveCurrencyCode("USD")
                .expiresAt("2026-05-26T05:35:00Z")
                .build();
    }

    private static ExchangeResponse stubExchange() {
        return ExchangeResponse.builder()
                .publicId("ex-1")
                .exchangeType("EXCHANGE")
                .fromCurrencyCode("KRW")
                .toCurrencyCode("USD")
                .amount("100000.0000")
                .exchangeRate("1380.0000")
                .fee("500.0000")
                .receiveAmount("72.1014")
                .receiveCurrencyCode("USD")
                .status("COMPLETED")
                .exchangedAt("2026-05-26T05:30:00Z")
                .build();
    }
}
