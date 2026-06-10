package com.gb.wallet.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.gb.common.exception.BusinessException;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.client.dto.VerifyInitResult;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link MockBankClient}의 HTTP 호출/응답 매핑 검증.
 * {@link MockRestServiceServer}로 RestClient 호출을 가로채 응답을 stub한다 (실제 네트워크 없음).
 */
class MockBankClientTest {

    private static final String BASE_URL = "http://mock-bank.test";
    private static final String VERIFY_PATH = "/api/v1/bank/accounts/verify";
    private static final String CONFIRM_PATH = "/api/v1/bank/accounts/confirm";
    private static final String WITHDRAW_PATH = "/api/v1/bank/transfers/withdrawal";
    private static final String PAYOUT_PATH = "/api/v1/bank/transfers/payout";

    private MockBankClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        // 운영에서는 Spring Boot가 SNAKE_CASE로 설정된 ObjectMapper로 Jackson 컨버터를 만들어 RestClient.Builder에 주입한다.
        // 테스트는 그 환경을 재현해야 — RestClient.builder() 정적 호출은 기본 ObjectMapper(camelCase)를 써서
        // {"account_holder_name": ...} 응답을 record(accountHolderName)에 매핑하지 못한다.
        ObjectMapper snakeCaseMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        MappingJackson2HttpMessageConverter jacksonConverter =
                new MappingJackson2HttpMessageConverter(snakeCaseMapper);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(jacksonConverter);
                });
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MockBankClient(builder.build(), snakeCaseMapper);
    }

    @Test
    @DisplayName("inquiry 200: data.account_holder_name 매핑 + 요청 본문 검증")
    void inquiry_success() {
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/inquiry"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"bank_code\":\"004\",\"account_number\":\"12345\"}"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"account_holder_name\":\"홍길동\"},\"message\":\"ok\"}"));

        AccountHolder holder = client.inquiry("004", "12345");

        assertThat(holder.accountHolderName()).isEqualTo("홍길동");
        server.verify();
    }

    @Test
    @DisplayName("inquiry 404 BANK4040 → BusinessException(ACCOUNT4001)")
    void inquiry_notFound_mappedToAccount4001() {
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/inquiry"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4040\",\"message\":\"존재하지 않는 계좌\"}"));

        assertThatThrownBy(() -> client.inquiry("004", "00000"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("inquiry 5xx → BusinessException(COMMON5031)")
    void inquiry_serverError_mappedToCommon5031() {
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/inquiry"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK5000\",\"message\":\"Mock 내부 오류\"}"));

        assertThatThrownBy(() -> client.inquiry("004", "12345"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("inquiry 200 + 빈 본문(data null) → BusinessException(COMMON5031)")
    void inquiry_emptyBody_mappedToCommon5031() {
        // 200이지만 본문이 비어 data()가 null → null 가드(BANK5000 합성)가 COMMON5031로 매핑된다.
        // verify 경로엔 동일 가드 테스트가 있으나 inquiry엔 없어 회귀 사각지대였다.
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/inquiry"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertThatThrownBy(() -> client.inquiry("004", "12345"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("inquiry 200 + data.account_holder_name 누락 → BusinessException(COMMON5031)")
    void inquiry_missingHolderName_mappedToCommon5031() {
        // data는 있으나 account_holder_name이 null인 경계 — 가드의 세 번째 조건(holder null)을 검증.
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/inquiry"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{},\"message\":\"ok\"}"));

        assertThatThrownBy(() -> client.inquiry("004", "12345"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    // --- payout (현금화 지급) ---

    @Test
    @DisplayName("payout 200: data → PayoutResult 매핑 + 요청 본문(bank_code/account_number/amount/currency_code) + Idempotency-Key 헤더 검증")
    void payout_success() {
        server.expect(requestTo(BASE_URL + PAYOUT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "idem-payout-1"))
                .andExpect(content().json(
                        "{\"bank_code\":\"VCB\",\"account_number\":\"9876543210\","
                                + "\"amount\":\"500000.0000\",\"currency_code\":\"VND\"}"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"transaction_id\":\"mock-payout-1\","
                                + "\"status\":\"COMPLETED\",\"amount\":\"500000.0000\","
                                + "\"currency_code\":\"VND\",\"balance_after\":\"1500000.0000\"},\"message\":\"ok\"}"));

        PayoutResult result = client.payout("VCB", "9876543210", new BigDecimal("500000"), "VND", "idem-payout-1");

        assertThat(result.transactionId()).isEqualTo("mock-payout-1");
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.amount()).isEqualByComparingTo("500000");
        assertThat(result.currencyCode()).isEqualTo("VND");
        assertThat(result.balanceAfter()).isEqualByComparingTo("1500000");
        server.verify();
    }

    @Test
    @DisplayName("payout 400 BANK4002(잔액 부족) → BusinessException(ACCOUNT4003)")
    void payout_bank4002_mappedToAccount4003() {
        server.expect(requestTo(BASE_URL + PAYOUT_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4002\",\"message\":\"출금 잔액 부족\"}"));

        assertThatThrownBy(() -> client.payout("VCB", "9876543210", new BigDecimal("500000"), "VND", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.INSUFFICIENT_LINKED_ACCOUNT_BALANCE);
    }

    @Test
    @DisplayName("payout 404 BANK4040(계좌 없음) → BusinessException(ACCOUNT4001)")
    void payout_bank4040_mappedToAccount4001() {
        server.expect(requestTo(BASE_URL + PAYOUT_PATH))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4040\",\"message\":\"존재하지 않는 계좌\"}"));

        assertThatThrownBy(() -> client.payout("VCB", "0000000000", new BigDecimal("100"), "VND", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("payout 400 BANK4003(예금주 불일치/인증 실패) → BusinessException(ACCOUNT4002)")
    void payout_bank4003_mappedToAccount4002() {
        server.expect(requestTo(BASE_URL + PAYOUT_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4003\",\"message\":\"예금주 불일치\"}"));

        assertThatThrownBy(() -> client.payout("VCB", "9876543210", new BigDecimal("100"), "VND", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED);
    }

    @Test
    @DisplayName("payout 401 BANK4010(유효하지 않은 토큰) → BusinessException(ACCOUNT4006 미인증 계좌)")
    void payout_bank4010_mappedToAccount4006() {
        server.expect(requestTo(BASE_URL + PAYOUT_PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4010\",\"message\":\"유효하지 않은 토큰\"}"));

        assertThatThrownBy(() -> client.payout("VCB", "9876543210", new BigDecimal("100"), "VND", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.UNVERIFIED_ACCOUNT);
    }

    @Test
    @DisplayName("payout 5xx → BusinessException(COMMON5031) — 외부 일시 장애/네트워크 실패 매핑")
    void payout_serverError_mappedToCommon5031() {
        server.expect(requestTo(BASE_URL + PAYOUT_PATH))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK5000\",\"message\":\"Mock 내부 오류\"}"));

        assertThatThrownBy(() -> client.payout("VCB", "9876543210", new BigDecimal("100"), "VND", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    // --- withdraw (충전 출금) ---

    @Test
    @DisplayName("withdraw 200: data → WithdrawalResult 매핑 + 요청 본문(account_token/amount/currency_code) 검증")
    void withdraw_success() {
        server.expect(requestTo(BASE_URL + WITHDRAW_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"account_token\":\"tok-abc\",\"amount\":\"1530000.0000\",\"currency_code\":\"KRW\"}"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"transaction_id\":\"mock-tx-1\","
                                + "\"status\":\"COMPLETED\",\"amount\":\"1530000.0000\","
                                + "\"currency_code\":\"KRW\",\"balance_after\":\"8470000.0000\"},\"message\":\"ok\"}"));

        WithdrawalResult result = client.withdraw("tok-abc", new BigDecimal("1530000"), "KRW", "idem-1");

        assertThat(result.transactionId()).isEqualTo("mock-tx-1");
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.amount()).isEqualByComparingTo("1530000");
        assertThat(result.currencyCode()).isEqualTo("KRW");
        assertThat(result.balanceAfter()).isEqualByComparingTo("8470000");
        server.verify();
    }

    @Test
    @DisplayName("withdraw: Idempotency-Key 헤더가 Mock 은행으로 그대로 forward된다(§5-2)")
    void withdraw_idempotencyKey_header_forwarded() {
        server.expect(requestTo(BASE_URL + WITHDRAW_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "idem-key-xyz"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"transaction_id\":\"t\",\"status\":\"COMPLETED\","
                                + "\"amount\":\"100.0000\",\"currency_code\":\"KRW\",\"balance_after\":\"0.0000\"}}"));

        client.withdraw("tok", new BigDecimal("100"), "KRW", "idem-key-xyz");

        server.verify();
    }

    @Test
    @DisplayName("withdraw 400 BANK4002(출금 잔액부족) → BusinessException(ACCOUNT4003)")
    void withdraw_bank4002_mappedToAccount4003() {
        server.expect(requestTo(BASE_URL + WITHDRAW_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4002\",\"message\":\"출금 잔액 부족\"}"));

        assertThatThrownBy(() -> client.withdraw("tok", new BigDecimal("1000"), "KRW", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.INSUFFICIENT_LINKED_ACCOUNT_BALANCE);
    }

    @Test
    @DisplayName("withdraw 401 BANK4010(유효하지 않은 토큰) → BusinessException(ACCOUNT4006)")
    void withdraw_bank4010_mappedToAccount4006() {
        server.expect(requestTo(BASE_URL + WITHDRAW_PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4010\",\"message\":\"유효하지 않은 토큰\"}"));

        assertThatThrownBy(() -> client.withdraw("bad-tok", new BigDecimal("1000"), "KRW", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.UNVERIFIED_ACCOUNT);
    }

    @Test
    @DisplayName("withdraw 404 BANK4040(계좌 없음) → BusinessException(ACCOUNT4001)")
    void withdraw_bank4040_mappedToAccount4001() {
        server.expect(requestTo(BASE_URL + WITHDRAW_PATH))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4040\",\"message\":\"존재하지 않는 계좌\"}"));

        assertThatThrownBy(() -> client.withdraw("tok", new BigDecimal("1000"), "KRW", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("withdraw 5xx → BusinessException(COMMON5031)")
    void withdraw_serverError_mappedToCommon5031() {
        server.expect(requestTo(BASE_URL + WITHDRAW_PATH))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK5000\",\"message\":\"Mock 내부 오류\"}"));

        assertThatThrownBy(() -> client.withdraw("tok", new BigDecimal("1000"), "KRW", "k"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    // --- initVerify (계좌 인증 1단계: 1원 입금 + 인증번호 생성) ---

    @Test
    @DisplayName("initVerify 200: data.pending/expires_at 매핑 + 요청 본문(bank_code/account_number/holder_name) 검증")
    void initVerify_success() {
        server.expect(requestTo(BASE_URL + VERIFY_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"bank_code\":\"004\",\"account_number\":\"1234567890\",\"holder_name\":\"홍길동\"}"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"pending\":true,"
                                + "\"expires_at\":\"2026-06-09T12:44:56Z\"},\"message\":\"ok\"}"));

        VerifyInitResult result = client.initVerify("004", "1234567890", "홍길동");

        assertThat(result.pending()).isTrue();
        assertThat(result.expiresAt()).isEqualTo("2026-06-09T12:44:56Z");
        server.verify();
    }

    @Test
    @DisplayName("initVerify 400 BANK4003 → BusinessException(ACCOUNT4002 인증 실패)")
    void initVerify_holderMismatch_mappedToAccount4002() {
        server.expect(requestTo(BASE_URL + VERIFY_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4003\",\"message\":\"예금주 불일치\"}"));

        assertThatThrownBy(() -> client.initVerify("004", "1234567890", "임꺽정"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED);
    }

    @Test
    @DisplayName("initVerify 404 BANK4040 → BusinessException(ACCOUNT4001 없는 계좌)")
    void initVerify_accountNotFound_mappedToAccount4001() {
        server.expect(requestTo(BASE_URL + VERIFY_PATH))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4040\",\"message\":\"존재하지 않는 계좌\"}"));

        assertThatThrownBy(() -> client.initVerify("004", "0000000000", "홍길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("initVerify 200 + 빈 본문(data null) → BusinessException(COMMON5031)")
    void initVerify_emptyBody_mappedToCommon5031() {
        // 본문이 빈 객체 → envelope.data() 가 null로 떨어져 BANK5000 합성 후 매핑.
        server.expect(requestTo(BASE_URL + VERIFY_PATH))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertThatThrownBy(() -> client.initVerify("004", "1234567890", "홍길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("initVerify 200 + pending=false → BusinessException(COMMON5031) — 가드 세 번째 조건(!pending) 경계")
    void initVerify_pendingFalse_mappedToCommon5031() {
        // 200인데 pending=false는 계약 위반(입금이 시작되지 않음) — null 가드와 동일하게 BANK5000 합성.
        server.expect(requestTo(BASE_URL + VERIFY_PATH))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"pending\":false},\"message\":\"ok\"}"));

        assertThatThrownBy(() -> client.initVerify("004", "1234567890", "홍길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("initVerify 5xx → BusinessException(COMMON5031)")
    void initVerify_serverError_mappedToCommon5031() {
        server.expect(requestTo(BASE_URL + VERIFY_PATH))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK5000\",\"message\":\"Mock 내부 오류\"}"));

        assertThatThrownBy(() -> client.initVerify("004", "1234567890", "홍길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    // --- confirmVerify (계좌 인증 2단계: 인증번호 검증 → account_token 발급) ---

    @Test
    @DisplayName("confirmVerify 200: data.account_token 매핑 + 요청 본문(bank_code/account_number/code) 검증")
    void confirmVerify_success() {
        server.expect(requestTo(BASE_URL + CONFIRM_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"bank_code\":\"004\",\"account_number\":\"1234567890\",\"code\":\"2814\"}"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"account_token\":\"tok-abcdef\"},\"message\":\"ok\"}"));

        AccountToken token = client.confirmVerify("004", "1234567890", "2814");

        assertThat(token.accountToken()).isEqualTo("tok-abcdef");
        server.verify();
    }

    @Test
    @DisplayName("confirmVerify 400 BANK4005(인증번호 불일치) → BusinessException(ACCOUNT4008)")
    void confirmVerify_bank4005_mappedToAccount4008() {
        server.expect(requestTo(BASE_URL + CONFIRM_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4005\",\"message\":\"인증번호 불일치\"}"));

        assertThatThrownBy(() -> client.confirmVerify("004", "1234567890", "0000"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.VERIFY_CODE_INVALID);
    }

    @Test
    @DisplayName("confirmVerify 400 BANK4006(세션 없음/만료) → BusinessException(ACCOUNT4009)")
    void confirmVerify_bank4006_mappedToAccount4009() {
        server.expect(requestTo(BASE_URL + CONFIRM_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4006\",\"message\":\"인증 세션 없음/만료\"}"));

        assertThatThrownBy(() -> client.confirmVerify("004", "1234567890", "2814"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.VERIFY_SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("confirmVerify 400 BANK4007(이미 사용된 코드) → BusinessException(ACCOUNT4008 — 코드 무효와 동일 처리)")
    void confirmVerify_bank4007_mappedToAccount4008() {
        server.expect(requestTo(BASE_URL + CONFIRM_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4007\",\"message\":\"이미 사용된 코드\"}"));

        assertThatThrownBy(() -> client.confirmVerify("004", "1234567890", "2814"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.VERIFY_CODE_INVALID);
    }

    @Test
    @DisplayName("confirmVerify 200 + data.account_token 누락 → BusinessException(COMMON5031)")
    void confirmVerify_missingToken_mappedToCommon5031() {
        // 본문이 빈 객체 → envelope.data() 가 null로 떨어져 BANK5000 합성 후 매핑.
        server.expect(requestTo(BASE_URL + CONFIRM_PATH))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertThatThrownBy(() -> client.confirmVerify("004", "1234567890", "2814"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("confirmVerify 5xx → BusinessException(COMMON5031)")
    void confirmVerify_serverError_mappedToCommon5031() {
        server.expect(requestTo(BASE_URL + CONFIRM_PATH))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK5000\",\"message\":\"Mock 내부 오류\"}"));

        assertThatThrownBy(() -> client.confirmVerify("004", "1234567890", "2814"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("inquiry: 전역 SNAKE_CASE 설정 없이도 @JsonProperty로 매핑된다")
    void inquiry_doesNotDependOnGlobalSnakeCaseStrategy() {
        // 기본(camelCase) ObjectMapper로 RestClient를 구성해도 매핑이 깨지지 않아야 한다.
        // 위 setUp의 snake_case 케이스는 운영 환경 보장용이고, 이 케이스는 어댑터가
        // 전역 설정에 의존하지 않음을 별개로 증명한다.
        ObjectMapper defaultMapper = new ObjectMapper(); // SNAKE_CASE 미설정
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(defaultMapper);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(converter);
                });
        MockRestServiceServer localServer = MockRestServiceServer.bindTo(builder).build();
        MockBankClient localClient = new MockBankClient(builder.build(), defaultMapper);

        localServer.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/inquiry"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"account_holder_name\":\"홍길동\"},\"message\":\"ok\"}"));

        AccountHolder holder = localClient.inquiry("004", "12345");

        assertThat(holder.accountHolderName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("initVerify·confirmVerify: 전역 SNAKE_CASE 설정 없이도 @JsonProperty로 envelope 매핑된다")
    void verifyFlow_doesNotDependOnGlobalSnakeCaseStrategy() {
        // inquiry와 같은 보장. initVerify(expires_at)·confirmVerify(account_token) 모두 snake_case 필드라
        // 어댑터가 전역 Jackson 설정에 의존하지 않음을 별도 검증한다. inquiry 한 곳만 검증하면
        // 인증 경로가 깨지는 회귀를 못 잡는다.
        ObjectMapper defaultMapper = new ObjectMapper(); // SNAKE_CASE 미설정
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(defaultMapper);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(converter);
                });
        MockRestServiceServer localServer = MockRestServiceServer.bindTo(builder).build();
        MockBankClient localClient = new MockBankClient(builder.build(), defaultMapper);

        localServer.expect(requestTo(BASE_URL + VERIFY_PATH))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"pending\":true,"
                                + "\"expires_at\":\"2026-06-09T12:44:56Z\"},\"message\":\"ok\"}"));
        localServer.expect(requestTo(BASE_URL + CONFIRM_PATH))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"account_token\":\"tok-xyz\"},\"message\":\"ok\"}"));

        VerifyInitResult init = localClient.initVerify("004", "1234567890", "홍길동");
        AccountToken token = localClient.confirmVerify("004", "1234567890", "2814");

        assertThat(init.expiresAt()).isEqualTo("2026-06-09T12:44:56Z");
        assertThat(token.accountToken()).isEqualTo("tok-xyz");
    }
}
