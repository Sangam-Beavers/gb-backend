package com.gb.wallet.domain.account.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.SupportedBankListResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.service.BankAccountService;
import com.gb.wallet.domain.account.service.HolderService;
import com.gb.wallet.domain.account.service.SupportedBankService;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
        com.gb.wallet.global.config.SecurityConfig.class
})
class AccountControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private BankAccountService bankAccountService;
    @MockitoBean private SupportedBankService supportedBankService;
    @MockitoBean private HolderService holderService;

    private static final String USER_ID = "test-uuid-1234";

    // --- POST /verify ---

    @Test
    @DisplayName("POST /verify 200: 정상 호출 시 ApiResponse(success=true)로 account_token 반환")
    void verify_정상() throws Exception {
        given(bankAccountService.verifyAccount(any()))
                .willReturn(VerifyAccountResponse.from(new AccountToken("tok-abcdef")));

        mockMvc.perform(post("/api/v1/accounts/verify")
                        .header("X-User-Public-Id", USER_ID)
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
                        .header("X-User-Public-Id", USER_ID)
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
                        .header("X-User-Public-Id", USER_ID)
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
                .given(bankAccountService).verifyAccount(any());

        mockMvc.perform(post("/api/v1/accounts/verify")
                        .header("X-User-Public-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "holder_name", "임꺽정"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT4002"));
    }

    @Test
    @DisplayName("POST /verify 400: X-User-Public-Id 헤더 누락 → COMMON4001 (헤더 필수)")
    void verify_헤더_누락() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "holder_name", "홍길동"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4001"));

        verifyNoInteractions(bankAccountService);
    }

    // --- POST /accounts ---

    @Test
    @DisplayName("POST /accounts 201: 정상 등록 → 201 Created + AccountResponse 반환 + service 호출")
    void register_정상_201() throws Exception {
        given(bankAccountService.registerAccount(eq(USER_ID), any()))
                .willReturn(stubAccountResponse());

        mockMvc.perform(post("/api/v1/accounts")
                        .header("X-User-Public-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "account_token", "tok-abcdef"))))
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
                        .header("X-User-Public-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "004",
                                "account_number", "1234567890",
                                "account_token", "tok-abcdef"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT4004"));
    }

    @Test
    @DisplayName("POST /accounts 400: 필수값 누락(account_token) → COMMON4001")
    void register_필수값_누락() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("X-User-Public-Id", USER_ID)
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
                        .header("X-User-Public-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bank_code", "0".repeat(21),
                                "account_number", "1234567890",
                                "account_token", "tok-abcdef"))))
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
                        .header("X-User-Public-Id", USER_ID)
                        .param("bankCode", "004")
                        .param("accountNumber", "1234567890"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_holder_name").value("홍길동"));
    }

    @Test
    @DisplayName("GET /holder 400: bankCode 누락(필수 @RequestParam) → COMMON4001")
    void holder_파라미터_누락() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/holder")
                        .header("X-User-Public-Id", USER_ID)
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
                        .header("X-User-Public-Id", USER_ID))
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
                        .header("X-User-Public-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accounts").isArray())
                .andExpect(jsonPath("$.data.accounts.length()").value(0));
    }

    // ----- helpers -----

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
