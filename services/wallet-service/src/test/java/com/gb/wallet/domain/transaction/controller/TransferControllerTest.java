package com.gb.wallet.domain.transaction.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.global.config.WebConfig;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link TransferController#executeTransfer}의 HTTP wiring 검증 — URL/메서드, @Valid, @RequestHeader,
 * @ResponseStatus(201), ApiResponse 래핑, BusinessException → ErrorResponse 변환까지.
 *
 * <p>{@link com.gb.common.exception.handler.GlobalExceptionHandler}는 {@code common-exception} 모듈에 있어
 * {@code @WebMvcTest} 기본 스캔에 잡히지 않는다. {@link com.gb.wallet.global.config.SecurityConfig}는
 * 같은 서비스지만 슬라이스 테스트의 컴포넌트 필터에서 제외되며, spring-security가 classpath에 있으면
 * 기본 보안 필터가 모든 요청을 401/403으로 차단한다. 두 빈을 {@code @Import}로 가져와 운영과 동일한
 * 경로(permitAll + 공용 예외 처리기)에서 검증한다 — AccountControllerTest와 동일 패턴.
 *
 * <p>Service 로직 자체는 {@link com.gb.wallet.domain.transaction.service.impl.TransferServiceImplExecuteTest}가
 * 담당. 여기는 HTTP 어댑터 경계만.
 */
@WebMvcTest(TransferController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.wallet.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class TransferControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private TransferService transferService;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final String USER = "sender-user-uuid";
    private static final String KEY = "idem-key-1";
    private static final String RECEIVER = "11111111-1111-1111-1111-111111111111";

    /** 인증된 요청용 JWT 주입(public_id claim = USER). 컨트롤러는 이 claim으로 송신자를 식별한다. */
    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER));
    }

    @Test
    @DisplayName("POST /transfers 201: 정상 호출 시 ApiResponse(success=true) + data 포함")
    void executeTransfer_정상_201() throws Exception {
        TransferExecuteResponse response = stubResponse();
        given(transferService.execute(eq(USER), eq(KEY), any())).willReturn(response);

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody("10000.0000"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("송금이 완료되었습니다."))
                .andExpect(jsonPath("$.data.public_id").value("new-tx-public-id"))
                .andExpect(jsonPath("$.data.amount").value("10000.0000"))
                .andExpect(jsonPath("$.data.fee").value("0.0000"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /transfers 400: @Valid 검증 실패(amount 형식 오류) → COMMON4001, Service 미호출")
    void executeTransfer_amount_형식오류_COMMON4001() throws Exception {
        Map<String, Object> body = validBody("not-a-number");

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(transferService, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("POST /transfers 400: INTERNAL receiver_public_id 누락 → COMMON4001 (TX1: 검증이 @Valid→도메인으로 이동, 서비스 도달 후 차단)")
    void executeTransfer_receiverPublicId_누락_COMMON4001() throws Exception {
        Map<String, Object> body = validBody("10000.0000");
        body.remove("receiver_public_id");
        // TX1: 무조건 @NotBlank를 제거했으므로 @Valid는 통과하고, 서비스(resolveScopeId)가 INTERNAL 필수
        //      검증으로 COMMON4001을 던진다. 웹 계층은 그 예외가 400으로 매핑되는지를 검증한다.
        given(transferService.execute(eq(USER), eq(KEY), any()))
                .willThrow(new BusinessException(CommonErrorCode.INVALID_REQUEST));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(transferService).execute(eq(USER), eq(KEY), any()); // 이제 @Valid를 통과해 서비스로 도달한다
    }

    @Test
    @DisplayName("TX1: POST /transfers 201 — REMITTANCE 바디(receiver 없음, bank_account 설정)가 @Valid 통과해 서비스에 도달")
    void executeTransfer_REMITTANCE_정상_201() throws Exception {
        // 무조건 @NotBlank였다면 receiver_public_id 부재로 @Valid에서 COMMON4001 거부돼 서비스에 도달조차
        // 못 했다(TX1). 이제 통과해 서비스가 호출되는지를 검증한다.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("transfer_type", "REMITTANCE");
        body.put("amount", "10000.0000");
        body.put("currency_code", "KRW");
        body.put("receive_currency_code", "KRW");
        body.put("memo", "해외송금");
        body.put("bank_account_public_id", "22222222-2222-2222-2222-222222222222");
        // receiver_public_id 없음
        given(transferService.execute(eq(USER), eq(KEY), any())).willReturn(stubResponse());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        verify(transferService).execute(eq(USER), eq(KEY), any()); // @Valid 통과 → 서비스 도달(TX1 핵심)
    }

    @Test
    @DisplayName("POST /transfers 400: Idempotency-Key 헤더 누락 → 400")
    void executeTransfer_idempotencyKey_누락() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        // Idempotency-Key 헤더 의도적으로 빠뜨림
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody("10000.0000"))))
                .andExpect(status().isBadRequest());

        verify(transferService, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("POST /transfers 400: Idempotency-Key 100자 초과 → @Size 위반 → COMMON4001, Service 미호출")
    void executeTransfer_idempotencyKey_길이초과_COMMON4001() throws Exception {
        // VARCHAR(100) 잘림→키 충돌→멱등 우회(이중 출금)의 입력단 차단(WTX-01). charge와 동일 @Size(max=100).
        String tooLong = "k".repeat(101);

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        .header("Idempotency-Key", tooLong)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody("10000.0000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(transferService, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("POST /transfers 400: Idempotency-Key 공백 → @NotBlank 위반 → COMMON4001, Service 미호출")
    void executeTransfer_idempotencyKey_공백_COMMON4001() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody("10000.0000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(transferService, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("POST /transfers 404: Service가 WALLET_NOT_FOUND throw → 404 + WALLET4001 코드")
    void executeTransfer_WALLET4001_404() throws Exception {
        willThrow(new BusinessException(WalletErrorCode.WALLET_NOT_FOUND))
                .given(transferService).execute(eq(USER), eq(KEY), any());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody("10000.0000"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("WALLET4001"));
    }

    @Test
    @DisplayName("POST /transfers 400: Service가 SELF_TRANSFER_NOT_ALLOWED throw → 400 + TRANSFER4004")
    void executeTransfer_TRANSFER4004_400() throws Exception {
        willThrow(new BusinessException(TransferErrorCode.SELF_TRANSFER_NOT_ALLOWED))
                .given(transferService).execute(eq(USER), eq(KEY), any());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authedJwt())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody("10000.0000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TRANSFER4004"));
    }

    @Test
    @DisplayName("POST /transfers 401: 토큰 없음 → AUTH4011, Service 미호출")
    void executeTransfer_토큰_없음_401() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody("10000.0000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verify(transferService, never()).execute(any(), any(), any());
    }

    // ----- helpers -----

    /** 정상 요청 본문(snake_case). 테스트마다 일부 키만 바꿔서 재사용. */
    private Map<String, Object> validBody(String amount) {
        // HashMap 기반 — remove로 필드 누락 시나리오 만들기 좋다.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("transfer_type", "INTERNAL_TRANSFER");
        body.put("amount", amount);
        body.put("currency_code", "KRW");
        body.put("receive_currency_code", "KRW");
        body.put("memo", "테스트");
        body.put("receiver_public_id", RECEIVER);
        return body;
    }

    private TransferExecuteResponse stubResponse() {
        return new TransferExecuteResponse(
                "new-tx-public-id", "INTERNAL_TRANSFER", "10000.0000", "KRW", "0.0000",
                null, "10000.0000", "KRW", "COMPLETED", "2026-06-01T04:15:30Z");
    }
}
