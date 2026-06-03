package com.gb.wallet.domain.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.security.RestAuthenticationEntryPoint;
import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.ChargeResponse;
import com.gb.wallet.domain.account.dto.response.SupportedBankListResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.service.BankAccountService;
import com.gb.wallet.domain.account.service.ChargeService;
import com.gb.wallet.domain.account.service.HolderService;
import com.gb.wallet.domain.account.service.SupportedBankService;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.config.WebConfig;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.security.CurrentUserPublicIdArgumentResolver;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link AccountController}의 HTTP wiring 검증 — URL/메서드, @Valid·@Validated, @RequestHeader,
 * @ResponseStatus(201), ApiResponse 래핑, BusinessException → ErrorResponse 변환까지.
 *
 * <p>{@link com.gb.common.exception.handler.GlobalExceptionHandler}는 {@code common-exception} 모듈에 있어
 * {@code @WebMvcTest} 기본 스캔에 잡히지 않는다. {@link com.gb.wallet.global.config.SecurityConfig}는
 * 같은 서비스지만 슬라이스 테스트의 컴포넌트 필터에서 제외되며, spring-security가 classpath에 있으면
 * 기본 보안 필터가 모든 요청을 401/403으로 차단해 wiring 검증이 불가능하다. 두 빈을 명시 {@code @Import}로
 * 가져와 운영과 동일한 경로(permitAll + 공용 예외 처리기)에서 검증한다.
 */
@WebMvcTest(AccountController.class)
@Import({
        com.gb.common.exception.handler.GlobalExceptionHandler.class,
        com.gb.wallet.global.config.SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        WebConfig.class,
        CurrentUserPublicIdArgumentResolver.class
})
@ActiveProfiles("test")
class AccountControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private BankAccountService bankAccountService;
    @MockitoBean private SupportedBankService supportedBankService;
    @MockitoBean private HolderService holderService;
    @MockitoBean private ChargeService chargeService;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단).
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final String USER_ID = "test-uuid-1234";
    private static final String ACCT_ID = "acct-uuid";

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

    // --- POST /verify ---

    @Test
    @DisplayName("POST /verify 200: 정상 호출 시 ApiResponse(success=true)로 account_token 반환")
    void verify_정상() throws Exception {
        given(bankAccountService.verifyAccount(any(), anyString()))
                .willReturn(VerifyAccountResponse.from(new AccountToken("tok-abcdef")));

        mockMvc.perform(post("/api/v1/accounts/verify")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "holder_name", "홍길동"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.account_token").value("tok-abcdef"));
    }

    @Test
    @DisplayName("POST /verify 400: 필수값 누락(holder_name) → COMMON4001로 변환")
    void verify_필수값_누락() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/verify")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(bankAccountService);
    }

    @Test
    @DisplayName("POST /verify 400: accountNumber 100자 초과 → @Size 위반 → COMMON4001")
    void verify_길이_초과() throws Exception {
        String tooLong = "0".repeat(101);
        mockMvc.perform(post("/api/v1/accounts/verify")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", tooLong,
                                "holder_name", "홍길동"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(bankAccountService);
    }

    @Test
    @DisplayName("POST /verify 400: Mock 은행 인증 실패(ACCOUNT4002)가 그대로 응답된다")
    void verify_은행_인증_실패() throws Exception {
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED))
                .given(bankAccountService).verifyAccount(any(), anyString());

        mockMvc.perform(post("/api/v1/accounts/verify")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "holder_name", "임꺽정"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT4002"));
    }

    @Test
    @DisplayName("POST /verify 401: 토큰 없음 → AUTH4011 (보호 엔드포인트), service 미호출")
    void verify_토큰_없음_401() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "holder_name", "홍길동"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(bankAccountService);
    }

    @Test
    @DisplayName("POST /verify 401: 만료/위조 토큰 → AUTH4011 (BearerTokenAuthenticationFilter 경로), service 미호출")
    void verify_만료토큰_401() throws Exception {
        // 실제 Authorization 헤더로 보내 BearerTokenAuthenticationFilter가 JwtDecoder.decode를 타게 한다
        // (jwt() 후처리기는 필터를 우회하므로 이 경로를 검증 못 함). decode가 만료 예외를 던지면
        // oauth2ResourceServer의 entry point가 AUTH4011로 응답해야 한다(빈 body 기본응답이면 회귀).
        // BadJwtException = "토큰이 나쁨"(만료·서명·형식) → InvalidBearerTokenException(401)으로 변환돼
        // entry point를 탄다. 일반 JwtException은 "디코더 장애"로 분류돼 500이 되므로 만료 재현엔 부적합.
        given(jwtDecoder.decode(anyString()))
                .willThrow(new BadJwtException("Jwt expired at 2026-06-02T02:27:55Z"));

        mockMvc.perform(post("/api/v1/accounts/verify")
                        .header("Authorization", "Bearer expired.jwt.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "holder_name", "홍길동"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(bankAccountService);
    }

    @Test
    @DisplayName("POST /verify 429: service가 ACCOUNT4005(rate-limit 초과) 던지면 → 429 + code")
    void verify_rate_limit_429() throws Exception {
        willThrow(new BusinessException(AccountErrorCode.VERIFICATION_RATE_LIMITED))
                .given(bankAccountService).verifyAccount(any(), anyString());

        mockMvc.perform(post("/api/v1/accounts/verify")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "holder_name", "홍길동"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT4005"));
    }

    @Test
    @DisplayName("POST /verify - X-Forwarded-For가 있으면 맨 앞 IP를 clientIp(rate-limit 키)로 service에 전달")
    void verify_forwardsClientIpFromXff() throws Exception {
        given(bankAccountService.verifyAccount(any(), anyString()))
                .willReturn(VerifyAccountResponse.from(new AccountToken("tok")));

        mockMvc.perform(post("/api/v1/accounts/verify")
                        .with(authedJwt())
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "holder_name", "홍길동"))))
                .andExpect(status().isOk());

        ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
        verify(bankAccountService).verifyAccount(any(), ipCaptor.capture());
        assertThat(ipCaptor.getValue()).isEqualTo("203.0.113.9");
    }

    // --- POST /accounts ---

    @Test
    @DisplayName("POST /accounts 201: 정상 등록 → 201 Created + AccountResponse 반환 + service 호출")
    void register_정상_201() throws Exception {
        given(bankAccountService.registerAccount(eq(USER_ID), any()))
                .willReturn(stubAccountResponse());

        mockMvc.perform(post("/api/v1/accounts")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "account_token", "tok-abcdef",
                                "holder_name", "홍길동"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.bank_code").value("004"))
                .andExpect(jsonPath("$.data.is_primary").value(true))
                .andExpect(jsonPath("$.data.is_verified").value(true));

        verify(bankAccountService).registerAccount(eq(USER_ID), any());
    }

    @Test
    @DisplayName("POST /accounts 409: 중복 등록(ACCOUNT4004) 매핑")
    void register_중복() throws Exception {
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED))
                .given(bankAccountService).registerAccount(eq(USER_ID), any());

        mockMvc.perform(post("/api/v1/accounts")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "account_token", "tok-abcdef",
                                "holder_name", "홍길동"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT4004"));
    }

    @Test
    @DisplayName("POST /accounts 400: 필수값 누락(account_token) → COMMON4001")
    void register_필수값_누락() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(bankAccountService);
    }

    @Test
    @DisplayName("POST /accounts 400: bank_code 20자 초과 → @Size 위반 → COMMON4001")
    void register_은행코드_길이_초과() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "0".repeat(21),
                                "account_number", "1234567890",
                                "account_token", "tok-abcdef",
                                "holder_name", "홍길동"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(bankAccountService);
    }

    // --- GET /holder ---

    @Test
    @DisplayName("GET /holder 200: 정상 시 ApiResponse + account_holder_name 반환")
    void holder_정상() throws Exception {
        given(holderService.getAccountHolder("004", "1234567890"))
                .willReturn(stubHolderResponse("홍길동"));

        mockMvc.perform(get("/api/v1/accounts/holder")
                        .with(authedJwt())
                        .param("bankCode", "004")
                        .param("accountNumber", "1234567890"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_holder_name").value("홍길동"));
    }

    @Test
    @DisplayName("GET /holder 400: bankCode 누락(필수 @RequestParam) → COMMON4001")
    void holder_파라미터_누락() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/holder")
                        .with(authedJwt())
                        .param("accountNumber", "1234567890"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(holderService);
    }

    // --- GET /supported-banks ---

    @Test
    @DisplayName("GET /supported-banks 200: 마스터 조회. 헤더 값 무관 — 결과만 매핑")
    void supportedBanks_정상() throws Exception {
        given(supportedBankService.getSupportedBanks())
                .willReturn(SupportedBankListResponse.from(java.util.List.of()));

        mockMvc.perform(get("/api/v1/accounts/supported-banks")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.banks").isArray());
    }

    // --- GET /accounts ---

    @Test
    @DisplayName("GET /accounts 200: 빈 결과도 200 + accounts:[]")
    void getMyAccounts_빈_결과() throws Exception {
        given(bankAccountService.getMyAccounts(USER_ID))
                .willReturn(AccountListResponse.from(java.util.List.of()));

        mockMvc.perform(get("/api/v1/accounts")
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accounts").isArray())
                .andExpect(jsonPath("$.data.accounts.length()").value(0));
    }

    @Test
    @DisplayName("GET /accounts 200: 비어있지 않은 목록을 snake_case로 직렬화(순서·마스킹·created_at 보존)")
    void getMyAccounts_목록_직렬화() throws Exception {
        BankAccount primary = accountEntity("acct-1", "004", "KB국민은행", "12345678901234",
                true, true, LocalDateTime.of(2026, 5, 29, 10, 0, 0));
        BankAccount second = accountEntity("acct-2", "088", "신한은행", "98765432109876",
                false, false, LocalDateTime.of(2026, 5, 28, 9, 0, 0));
        given(bankAccountService.getMyAccounts(USER_ID))
                .willReturn(AccountListResponse.from(List.of(primary, second)));

        mockMvc.perform(get("/api/v1/accounts").with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts.length()").value(2))
                .andExpect(jsonPath("$.data.accounts[0].account_public_id").value("acct-1"))
                .andExpect(jsonPath("$.data.accounts[0].bank_code").value("004"))
                .andExpect(jsonPath("$.data.accounts[0].account_number_masked").value("123*********34"))
                .andExpect(jsonPath("$.data.accounts[0].is_primary").value(true))
                .andExpect(jsonPath("$.data.accounts[0].is_verified").value(true))
                .andExpect(jsonPath("$.data.accounts[0].created_at").value("2026-05-29T10:00:00Z"))
                .andExpect(jsonPath("$.data.accounts[1].account_public_id").value("acct-2"))
                .andExpect(jsonPath("$.data.accounts[1].is_primary").value(false))
                .andExpect(jsonPath("$.data.accounts[1].is_verified").value(false));
    }

    // --- PATCH /{id}/primary ---

    @Test
    @DisplayName("PATCH /{id}/primary 200: 정상 변경 → ApiResponse(success=true) + is_primary=true, service 호출")
    void changePrimary_정상_200() throws Exception {
        given(bankAccountService.changePrimary(USER_ID, ACCT_ID))
                .willReturn(stubAccountResponse());

        mockMvc.perform(patch("/api/v1/accounts/{id}/primary", ACCT_ID)
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.is_primary").value(true))
                .andExpect(jsonPath("$.data.account_public_id").value("acct-uuid"));

        verify(bankAccountService).changePrimary(USER_ID, ACCT_ID);
    }

    @Test
    @DisplayName("PATCH /{id}/primary 200: 이미 주 계좌여도 멱등 성공(컨트롤러 관점) → 200 + is_primary=true")
    void changePrimary_이미_주계좌_멱등_200() throws Exception {
        // 멱등성은 service가 보장한다(이미 주 계좌면 부수효과 없이 성공). 컨트롤러는 동일하게 200 + 변경 결과를 래핑한다.
        given(bankAccountService.changePrimary(USER_ID, ACCT_ID))
                .willReturn(stubAccountResponse());

        mockMvc.perform(patch("/api/v1/accounts/{id}/primary", ACCT_ID)
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.is_primary").value(true));

        verify(bankAccountService).changePrimary(USER_ID, ACCT_ID);
    }

    @Test
    @DisplayName("PATCH /{id}/primary 404: 없는/타인 계좌(ACCOUNT4001) → 404 + code")
    void changePrimary_없는_계좌_404() throws Exception {
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND))
                .given(bankAccountService).changePrimary(anyString(), anyString());

        mockMvc.perform(patch("/api/v1/accounts/{id}/primary", ACCT_ID)
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACCOUNT4001"));
    }

    @Test
    @DisplayName("PATCH /{id}/primary 503: 분산락 획득 실패(COMMON5031) → 503 + code")
    void changePrimary_락_실패_503() throws Exception {
        willThrow(new BusinessException(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE))
                .given(bankAccountService).changePrimary(anyString(), anyString());

        mockMvc.perform(patch("/api/v1/accounts/{id}/primary", ACCT_ID)
                        .with(authedJwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON5031"));
    }

    @Test
    @DisplayName("PATCH /{id}/primary 401: 토큰 없음 → AUTH4011, service 미호출")
    void changePrimary_토큰_없음_401() throws Exception {
        mockMvc.perform(patch("/api/v1/accounts/{id}/primary", ACCT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verify(bankAccountService, never()).changePrimary(any(), any());
    }

    // --- DELETE /{id} ---

    @Test
    @DisplayName("DELETE /{id} 200: 정상 삭제(soft-delete) → 200 + data:null, service 호출")
    void deleteAccount_정상_200() throws Exception {
        // void 메서드라 스텁 없이 호출(기본 do-nothing).
        mockMvc.perform(delete("/api/v1/accounts/{id}", ACCT_ID)
                        .with(authedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // ApiResponse는 @JsonInclude(ALWAYS)라 data:null이 키째 노출된다 — 삭제 응답에 payload 없음을 단언.
                .andExpect(jsonPath("$.data").isEmpty());

        verify(bankAccountService).deleteAccount(USER_ID, ACCT_ID);
    }

    @Test
    @DisplayName("DELETE /{id} 404: 없는/타인/이미 비활성 계좌(ACCOUNT4001) → 404 + code")
    void deleteAccount_없는_계좌_404() throws Exception {
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND))
                .given(bankAccountService).deleteAccount(anyString(), anyString());

        mockMvc.perform(delete("/api/v1/accounts/{id}", ACCT_ID)
                        .with(authedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACCOUNT4001"));
    }

    @Test
    @DisplayName("DELETE /{id} 401: 토큰 없음 → AUTH4011, service 미호출")
    void deleteAccount_토큰_없음_401() throws Exception {
        mockMvc.perform(delete("/api/v1/accounts/{id}", ACCT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verify(bankAccountService, never()).deleteAccount(any(), any());
    }

    @Test
    @DisplayName("DELETE /{id} 400: path id 36자 초과 → @Size 위반 → COMMON4001, service 미호출")
    void deleteAccount_path_길이_초과_400() throws Exception {
        String tooLong = "a".repeat(37); // @Size(max=36) 위반 → ConstraintViolationException → COMMON4001
        mockMvc.perform(delete("/api/v1/accounts/{id}", tooLong)
                        .with(authedJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verify(bankAccountService, never()).deleteAccount(any(), any());
    }

    // --- POST /{id}/charge ---

    @Test
    @DisplayName("POST /{id}/charge 201: 정상 충전 → 201 + ChargeResponse(snake_case), service 호출")
    void charge_정상_201() throws Exception {
        given(chargeService.charge(eq(USER_ID), eq(ACCT_ID), eq("idem-1"), any(), anyString()))
                .willReturn(stubChargeResponse());

        performValidCharge()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.public_id").value("charge-uuid"))
                .andExpect(jsonPath("$.data.account_public_id").value(ACCT_ID))
                .andExpect(jsonPath("$.data.amount").value("1530000.0000"))
                .andExpect(jsonPath("$.data.currency_code").value("KRW"))
                .andExpect(jsonPath("$.data.wallet_balance").value("5430000.0000"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(chargeService).charge(eq(USER_ID), eq(ACCT_ID), eq("idem-1"), any(), anyString());
    }

    @Test
    @DisplayName("POST /{id}/charge - X-Forwarded-For가 있으면 맨 앞 IP를 clientIp로 service에 전달")
    void charge_forwardsClientIpFromXff() throws Exception {
        given(chargeService.charge(eq(USER_ID), eq(ACCT_ID), eq("idem-1"), any(), anyString()))
                .willReturn(stubChargeResponse());

        mockMvc.perform(post("/api/v1/accounts/{id}/charge", ACCT_ID)
                        .with(authedJwt())
                        .header("Idempotency-Key", "idem-1")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "1530000"))))
                .andExpect(status().isCreated());

        ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
        verify(chargeService).charge(eq(USER_ID), eq(ACCT_ID), eq("idem-1"), any(), ipCaptor.capture());
        assertThat(ipCaptor.getValue()).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("POST /{id}/charge - X-Forwarded-For가 없으면 getRemoteAddr()(127.0.0.1)로 fallback")
    void charge_fallbackToRemoteAddrWhenNoXff() throws Exception {
        given(chargeService.charge(eq(USER_ID), eq(ACCT_ID), eq("idem-1"), any(), anyString()))
                .willReturn(stubChargeResponse());

        performValidCharge().andExpect(status().isCreated());

        ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
        verify(chargeService).charge(eq(USER_ID), eq(ACCT_ID), eq("idem-1"), any(), ipCaptor.capture());
        assertThat(ipCaptor.getValue()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("POST /{id}/charge 401: 토큰 없음 → AUTH4011, service 미호출")
    void charge_토큰_없음_401() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/charge", ACCT_ID)
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "1530000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(chargeService);
    }

    @Test
    @DisplayName("POST /{id}/charge 401: 토큰은 유효하나 public_id claim 누락 → AUTH4011(resolver fail-fast), service 미호출")
    void charge_publicIdClaim_누락_401() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/charge", ACCT_ID)
                        .with(jwtWithoutPublicId())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "1530000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH4011"));

        verifyNoInteractions(chargeService);
    }

    @Test
    @DisplayName("POST /{id}/charge 400: Idempotency-Key 헤더 누락 → COMMON4001, service 미호출")
    void charge_idempotencyHeader_누락() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/charge", ACCT_ID)
                        .with(authedJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "1530000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(chargeService);
    }

    @Test
    @DisplayName("POST /{id}/charge 400: amount 0/음수/형식 오류/누락 → COMMON4001, service 미호출")
    void charge_amount_검증_실패() throws Exception {
        for (String bad : List.of("0", "-100", "1.23456")) {
            mockMvc.perform(post("/api/v1/accounts/{id}/charge", ACCT_ID)
                            .with(authedJwt())
                            .header("Idempotency-Key", "idem-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("amount", bad))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON4001"));
        }
        // amount 필드 누락(빈 객체)
        mockMvc.perform(post("/api/v1/accounts/{id}/charge", ACCT_ID)
                        .with(authedJwt())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(chargeService);
    }

    @Test
    @DisplayName("POST /{id}/charge 403: service가 ACCOUNT4006 던지면 → 403 + code")
    void charge_service_ACCOUNT4006_403() throws Exception {
        willThrow(new BusinessException(AccountErrorCode.UNVERIFIED_ACCOUNT))
                .given(chargeService).charge(anyString(), anyString(), anyString(), any(), anyString());

        performValidCharge()
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT4006"));
    }

    @Test
    @DisplayName("POST /{id}/charge 422: service가 ACCOUNT4007(한도 초과) 던지면 → 422 + code")
    void charge_service_ACCOUNT4007_422() throws Exception {
        willThrow(new BusinessException(AccountErrorCode.CHARGE_LIMIT_EXCEEDED))
                .given(chargeService).charge(anyString(), anyString(), anyString(), any(), anyString());

        performValidCharge()
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT4007"));
    }

    @Test
    @DisplayName("POST /{id}/charge 404: service가 ACCOUNT4001 던지면 → 404 + code")
    void charge_service_ACCOUNT4001_404() throws Exception {
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND))
                .given(chargeService).charge(anyString(), anyString(), anyString(), any(), anyString());

        performValidCharge()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT4001"));
    }

    @Test
    @DisplayName("POST /{id}/charge 503: service가 COMMON5031(Mock 은행 장애) 던지면 → 503 + code")
    void charge_service_COMMON5031_503() throws Exception {
        willThrow(new BusinessException(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE))
                .given(chargeService).charge(anyString(), anyString(), anyString(), any(), anyString());

        performValidCharge()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON5031"));
    }

    // ----- helpers -----

    private ResultActions performValidCharge() throws Exception {
        return mockMvc.perform(post("/api/v1/accounts/{id}/charge", ACCT_ID)
                .with(authedJwt())
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", "1530000"))));
    }

    private ChargeResponse stubChargeResponse() {
        return ChargeResponse.builder()
                .publicId("charge-uuid")
                .accountPublicId(ACCT_ID)
                .amount("1530000.0000")
                .currencyCode("KRW")
                .walletBalance("5430000.0000")
                .status("COMPLETED")
                .createdAt("2026-05-30T04:15:30Z")
                .build();
    }

    /**
     * 목록 직렬화 검증용 BankAccount 엔티티. AccountListResponse.from(엔티티)이 실제 변환(마스킹·is_verified·
     * created_at)을 거치도록 실제 엔티티를 만든다. created_at은 비영속이라 auditing이 덮어쓰지 않으므로
     * reflection으로 주입한다(@WebMvcTest 슬라이스, JPA 없음).
     */
    private BankAccount accountEntity(String publicId, String bankCode, String bankName,
                                      String accountNumber, boolean primary, boolean verified,
                                      LocalDateTime createdAt) {
        Bank bank = Bank.builder()
                .code(bankCode).name(bankName).country("KR").isDomestic(true).isActive(true).build();
        BankAccount account = BankAccount.builder()
                .publicId(publicId)
                .userPublicId(USER_ID)
                .bank(bank)
                .accountNumber(accountNumber)
                .holderName("홍길동")
                .mockAccountToken(verified ? "tok" : null)
                .isVirtual(false)
                .isPrimary(primary)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(account, "createdAt", createdAt);
        return account;
    }

    private AccountResponse stubAccountResponse() {
        return AccountResponse.builder()
                .accountPublicId("acct-uuid")
                .bankCode("004")
                .bankName("KB국민은행")
                .accountNumberMasked("123*****890")
                .isPrimary(true)
                .isVirtual(false)
                .isVerified(true)
                .createdAt("2026-05-29T10:00:00Z")
                .build();
    }

    /**
     * AccountHolderResponse는 정적 팩토리(from)만 노출하므로 dto 의존을 통해 동일 인자로 생성.
     * 패키지 접근이 막혀 있으면 reflection이 필요할 수 있지만, 같은 public 정적 팩토리를 호출한다.
     */
    private AccountHolderResponse stubHolderResponse(String name) {
        return AccountHolderResponse.from(
                new com.gb.wallet.global.client.dto.AccountHolder(name));
    }
}
