package com.gb.wallet.domain.transaction.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.wallet.domain.transaction.dto.response.TransactionHistoryItemResponse;
import com.gb.wallet.domain.transaction.dto.response.TransactionListResponse;
import com.gb.wallet.domain.transaction.service.TransactionService;
import com.gb.wallet.global.config.WebConfig;
import com.gb.wallet.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link TransactionController}의 HTTP wiring 검증 — URL/메서드, @Validated(@RequestParam @Min/@Max),
 * @CurrentUserPublicId 해석, ApiResponse 래핑, BusinessException/검증 위반 → ErrorResponse 변환까지.
 *
 * <p>{@link com.gb.common.exception.handler.GlobalExceptionHandler}는 {@code common-exception} 모듈에 있어
 * {@code @WebMvcTest} 기본 스캔에 잡히지 않는다. {@link com.gb.wallet.global.config.SecurityConfig}는
 * 같은 서비스지만 슬라이스 테스트의 컴포넌트 필터에서 제외되며, spring-security가 classpath에 있으면
 * 기본 보안 필터가 모든 요청을 401/403으로 차단해 wiring 검증이 불가능하다. 두 빈을 명시 {@code @Import}로
 * 가져와 운영과 동일한 경로(permitAll + 공용 예외 처리기)에서 검증한다.
 *
 * <p>{@code @Validated} 컨트롤러의 {@code @RequestParam @Min/@Max} 위반은 ConstraintViolationException으로
 * 떨어져 GlobalExceptionHandler가 COMMON4001(400)으로 변환한다(CLAUDE.md §6).
 */
@WebMvcTest(TransactionController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.wallet.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class TransactionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private TransactionService transactionService;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final String USER_ID = "test-uuid-1234";

    /** 인증된 요청용 JWT 주입(public_id claim = USER_ID). 컨트롤러는 이 claim으로 사용자를 식별한다. */
    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER_ID));
    }

    /**
     * 토큰은 유효하나 {@code public_id} claim이 없는 JWT(=IdP Property Mapping 누락 시나리오).
     * CurrentUserPublicIdArgumentResolver가 AUTH4011로 fail-fast 하는 경로 검증용.
     */
    private static RequestPostProcessor jwtWithoutPublicId() {
        return jwt().jwt(j -> j.claim("sub", "no-mapping"));
    }

    // --- GET /me/transactions ---

    @Test
    @DisplayName("GET /me/transactions 200: 목록 반환 → ApiResponse(success=true) + snake_case 직렬화 + service 호출(0/20)")
    void getMyTransactions_정상() throws Exception {
        TransactionHistoryItemResponse charge = TransactionHistoryItemResponse.builder()
                .publicId("tx-uuid-1")
                .type("CHARGE")
                .direction("OUT")
                .status("COMPLETED")
                .amount("500000.0000")
                .currencyCode("KRW")
                .fee("0.0000")
                .receiveAmount(null)
                .receiveCurrencyCode(null)
                .receiverName(null)
                .createdAt("2026-05-26T04:15:30Z")
                .build();
        TransactionHistoryItemResponse remittance = TransactionHistoryItemResponse.builder()
                .publicId("tx-uuid-2")
                .type("REMITTANCE")
                .direction("OUT")
                .status("COMPLETED")
                .amount("100000.0000")
                .currencyCode("KRW")
                .fee("3000.0000")
                .receiveAmount("72.4500")
                .receiveCurrencyCode("USD")
                .receiverName("홍길동")
                .createdAt("2026-05-25T01:00:00Z")
                .build();
        given(transactionService.getMyTransactions(USER_ID, 0, 20))
                .willReturn(TransactionListResponse.of(List.of(charge, remittance), 0, 20, 2, 1));

        mockMvc.perform(get("/api/v1/wallets/me/transactions")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactions").isArray())
                .andExpect(jsonPath("$.data.transactions.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total_elements").value(2))
                .andExpect(jsonPath("$.data.total_pages").value(1))
                .andExpect(jsonPath("$.data.transactions[0].public_id").value("tx-uuid-1"))
                .andExpect(jsonPath("$.data.transactions[0].type").value("CHARGE"))
                .andExpect(jsonPath("$.data.transactions[0].amount").value("500000.0000"))
                .andExpect(jsonPath("$.data.transactions[0].currency_code").value("KRW"))
                .andExpect(jsonPath("$.data.transactions[0].created_at").value("2026-05-26T04:15:30Z"));

        verify(transactionService).getMyTransactions(eq(USER_ID), eq(0), eq(20));
    }

    @Test
    @DisplayName("GET /me/transactions 200: 거래 없음 → 200 + transactions:[] (빈 배열)")
    void getMyTransactions_빈_결과() throws Exception {
        given(transactionService.getMyTransactions(USER_ID, 0, 20))
                .willReturn(TransactionListResponse.of(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/wallets/me/transactions")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactions").isArray())
                .andExpect(jsonPath("$.data.transactions.length()").value(0))
                .andExpect(jsonPath("$.data.total_elements").value(0))
                .andExpect(jsonPath("$.data.total_pages").value(0));

        verify(transactionService).getMyTransactions(eq(USER_ID), eq(0), eq(20));
    }

    @Test
    @DisplayName("GET /me/transactions 400: page<0(@Min(0) 위반) → COMMON4001, service 미호출")
    void getMyTransactions_page_음수() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me/transactions")
                        .with(authedJwt())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("GET /me/transactions 400: size=0(@Min(1) 위반) → COMMON4001, service 미호출")
    void getMyTransactions_size_0() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me/transactions")
                        .with(authedJwt())
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("GET /me/transactions 400: size=101(@Max(100) 위반) → COMMON4001, service 미호출")
    void getMyTransactions_size_초과() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me/transactions")
                        .with(authedJwt())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("GET /me/transactions 401: 토큰 없음 → AUTH4011 (보호 엔드포인트), service 미호출")
    void getMyTransactions_토큰_없음_401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me/transactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("GET /me/transactions 401: 만료/위조 토큰 → AUTH4011 (BearerTokenAuthenticationFilter 경로), service 미호출")
    void getMyTransactions_만료토큰_401() throws Exception {
        // 실제 Authorization 헤더로 보내 BearerTokenAuthenticationFilter가 JwtDecoder.decode를 타게 한다
        // (jwt() 후처리기는 필터를 우회하므로 이 경로를 검증 못 함). BadJwtException은 InvalidBearerTokenException
        // (401)으로 변환돼 entry point(AUTH4011)를 탄다. 일반 JwtException은 500이 되므로 만료 재현엔 부적합.
        given(jwtDecoder.decode(anyString()))
                .willThrow(new BadJwtException("Jwt expired at 2026-06-02T02:27:55Z"));

        mockMvc.perform(get("/api/v1/wallets/me/transactions")
                        .header("Authorization", "Bearer expired.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("GET /me/transactions 401: 토큰은 유효하나 public_id claim 누락 → AUTH4011(resolver fail-fast), service 미호출")
    void getMyTransactions_publicIdClaim_누락_401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me/transactions")
                        .with(jwtWithoutPublicId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(transactionService);
    }
}
