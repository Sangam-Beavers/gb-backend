package com.gb.wallet.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.gb.common.exception.BusinessException;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.exception.code.AccountErrorCode;
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
    @DisplayName("withdraw/payout은 이번 PR에서 미구현 → UnsupportedOperationException")
    void unimplementedMethods_throwUnsupported() {
        assertThatThrownBy(() -> client.withdraw("tok", java.math.BigDecimal.ONE, "KRW", "k"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> client.payout("004", "12345", java.math.BigDecimal.ONE, "VND", "k"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("verify 200: data.account_token 매핑 + 요청 본문(bank_code/account_number/holder_name) 검증")
    void verify_success() {
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/verify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"bank_code\":\"004\",\"account_number\":\"1234567890\",\"holder_name\":\"홍길동\"}"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"account_token\":\"tok-abcdef\"},\"message\":\"ok\"}"));

        AccountToken token = client.verify("004", "1234567890", "홍길동");

        assertThat(token.accountToken()).isEqualTo("tok-abcdef");
        server.verify();
    }

    @Test
    @DisplayName("verify 400 BANK4003 → BusinessException(ACCOUNT4002 인증 실패)")
    void verify_holderMismatch_mappedToAccount4002() {
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/verify"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4003\",\"message\":\"예금주 불일치\"}"));

        assertThatThrownBy(() -> client.verify("004", "1234567890", "임꺽정"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED);
    }

    @Test
    @DisplayName("verify 404 BANK4040 → BusinessException(ACCOUNT4001 없는 계좌)")
    void verify_accountNotFound_mappedToAccount4001() {
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/verify"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK4040\",\"message\":\"존재하지 않는 계좌\"}"));

        assertThatThrownBy(() -> client.verify("004", "0000000000", "홍길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("verify 200 + data.account_token 누락 → BusinessException(COMMON5031)")
    void verify_missingToken_mappedToCommon5031() {
        // 본문이 빈 객체 → envelope.data() 가 null로 떨어져 BANK5000 합성 후 매핑.
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/verify"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertThatThrownBy(() -> client.verify("004", "1234567890", "홍길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("verify 5xx → BusinessException(COMMON5031)")
    void verify_serverError_mappedToCommon5031() {
        server.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/verify"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BANK5000\",\"message\":\"Mock 내부 오류\"}"));

        assertThatThrownBy(() -> client.verify("004", "1234567890", "홍길동"))
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
    @DisplayName("verify: 전역 SNAKE_CASE 설정 없이도 @JsonProperty로 envelope 매핑된다")
    void verify_doesNotDependOnGlobalSnakeCaseStrategy() {
        // inquiry와 같은 보장. verify도 envelope({data:{account_token}})이라 어댑터가 전역 Jackson 설정에
        // 의존하지 않음을 별도 검증한다. inquiry 한 곳만 검증하면 verify가 깨지는 회귀를 못 잡는다.
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

        localServer.expect(requestTo(BASE_URL + "/api/v1/bank/accounts/verify"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":true,\"data\":{\"account_token\":\"tok-xyz\"},\"message\":\"ok\"}"));

        AccountToken token = localClient.verify("004", "1234567890", "홍길동");

        assertThat(token.accountToken()).isEqualTo("tok-xyz");
    }
}
