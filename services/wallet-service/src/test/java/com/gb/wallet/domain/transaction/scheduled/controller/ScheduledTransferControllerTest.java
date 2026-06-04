package com.gb.wallet.domain.transaction.scheduled.controller;

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
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferResponse;
import com.gb.wallet.domain.transaction.scheduled.service.ScheduledTransferService;
import com.gb.wallet.global.config.WebConfig;
import com.gb.wallet.global.exception.code.TransferErrorCode;
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
 * {@link ScheduledTransferController#create}의 HTTP wiring 검증 — URL/메서드, @Valid, @ResponseStatus(201),
 * ApiResponse 래핑, BusinessException → ErrorResponse 변환. 특히 TX-PIN standing-order 게이트가 던지는
 * {@code TRANSFER4010 → 428}(PRECONDITION_REQUIRED) 매핑을 수동 송금 엔드포인트와 대칭으로 확인한다
 * (게이트 자체의 enforcement·순서는 {@link com.gb.wallet.domain.transaction.scheduled.service.impl.ScheduledTransferServiceImplTest}가 담당).
 *
 * <p>{@link com.gb.common.exception.handler.GlobalExceptionHandler}와 {@link com.gb.wallet.global.config.SecurityConfig}를
 * @Import해 운영과 동일한 경로(permitAll + 공용 예외 처리기)에서 검증한다 — TransferControllerTest와 동일 패턴.
 */
@WebMvcTest(ScheduledTransferController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.wallet.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class ScheduledTransferControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ScheduledTransferService scheduledTransferService;
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final String USER = "sender-user-uuid";
    private static final String BANK_ACC = "7g8h9i0j-1234-5678-90ab-cdef12345678";

    private static RequestPostProcessor authedJwt() {
        return jwt().jwt(j -> j.claim("public_id", USER));
    }

    @Test
    @DisplayName("POST /transfers/scheduled 201: 정상 설정 시 ApiResponse(success=true) + data 포함")
    void create_정상_201() throws Exception {
        given(scheduledTransferService.create(eq(USER), any())).willReturn(stubResponse());

        mockMvc.perform(post("/api/v1/transfers/scheduled")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("정기 송금이 설정되었습니다."))
                .andExpect(jsonPath("$.data.public_id").value("sched-pub-1"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /transfers/scheduled 428: 서비스가 PIN_VERIFICATION_REQUIRED throw → 428 + TRANSFER4010 (TX-PIN standing order)")
    void create_TRANSFER4010_428() throws Exception {
        willThrow(new BusinessException(TransferErrorCode.PIN_VERIFICATION_REQUIRED))
                .given(scheduledTransferService).create(eq(USER), any());

        mockMvc.perform(post("/api/v1/transfers/scheduled")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TRANSFER4010"));
    }

    @Test
    @DisplayName("POST /transfers/scheduled 400: @Valid 위반(amount 누락) → COMMON4001, 서비스 미호출")
    void create_amount_누락_COMMON4001() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("amount");

        mockMvc.perform(post("/api/v1/transfers/scheduled")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(scheduledTransferService, never()).create(any(), any());
    }

    @Test
    @DisplayName("POST /transfers/scheduled 401: 토큰 없음 → AUTH4011, 서비스 미호출")
    void create_토큰없음_401() throws Exception {
        mockMvc.perform(post("/api/v1/transfers/scheduled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verify(scheduledTransferService, never()).create(any(), any());
    }

    // ----- helpers -----

    /** @Valid를 통과하는 REMITTANCE 정기송금 본문(snake_case). 테스트마다 일부 키만 바꿔 재사용. */
    private Map<String, Object> validBody() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("transfer_type", "REMITTANCE");
        body.put("amount", "500000.0000");
        body.put("currency_code", "KRW");
        body.put("receive_currency_code", "KRW");
        body.put("frequency", "MONTHLY");
        body.put("schedule_day", 25);
        body.put("bank_account_public_id", BANK_ACC);
        body.put("memo", "매달 생활비");
        return body;
    }

    private ScheduledTransferResponse stubResponse() {
        return new ScheduledTransferResponse(
                "sched-pub-1", "REMITTANCE", "500000.0000", "KRW", "KRW",
                "MONTHLY", 25, "2026-06-25", null, "ACTIVE", "2026-06-01T04:15:30Z");
    }
}
