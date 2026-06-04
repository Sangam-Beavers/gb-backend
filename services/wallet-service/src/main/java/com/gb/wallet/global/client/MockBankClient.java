package com.gb.wallet.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 개발/스테이지용 {@link BankClient} 구현. 외부 Mock 은행 서버(Beaver/Quokka Bank)를 호출한다.
 *
 * <p>{@link #inquiry}/{@link #verify}/{@link #withdraw}(충전 출금)/{@link #payout}(현금화 지급) 4개 모두
 * 실동작한다. 인터페이스 시그니처는 동결.
 *
 * <p>운영 전환 시 {@code RealBankClient}(@Profile("prod"))가 추가되며 Service 코드는 그대로 둔다.
 *
 * <p><b>어댑터 경계 규칙:</b> 외부 시스템과의 wire-format 매핑(아래 private record)은
 * {@code @JsonProperty}로 <em>명시</em>한다. 본체 응답 DTO와 달리 전역 Jackson 설정
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE})에 의존하지 않는다 —
 * 외부 계약은 전역 설정 변경에 영향받지 않아야 한다. {@code payout}용 wire-format record를 추가할 때도
 * 동일 패턴을 따른다.
 * 외부 계약은 전역 설정 변경에 영향받지 않아야 한다.
 */
@Component
@Profile({"dev", "stage"})
public class MockBankClient implements BankClient {

    private static final String INQUIRY_PATH = "/api/v1/bank/accounts/inquiry";
    private static final String VERIFY_PATH = "/api/v1/bank/accounts/verify";
    private static final String WITHDRAW_PATH = "/api/v1/bank/transfers/withdrawal";
    private static final String PAYOUT_PATH = "/api/v1/bank/transfers/payout";

    private final RestClient bankRestClient;
    private final ObjectMapper objectMapper;

    public MockBankClient(RestClient bankRestClient, ObjectMapper objectMapper) {
        this.bankRestClient = bankRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AccountHolder inquiry(String bankCode, String accountNumber) {
        try {
            BankInquiryEnvelope body = bankRestClient.post()
                    .uri(INQUIRY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "bank_code", bankCode,
                            "account_number", accountNumber))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::translateError)
                    .body(BankInquiryEnvelope.class);
            if (body == null || body.data() == null || body.data().accountHolderName() == null) {
                throw BankErrorMapper.toBusinessException(new BankClientException(
                        "BANK5000", HttpStatus.INTERNAL_SERVER_ERROR, "Mock 은행 응답 본문이 비어있음"));
            }
            return new AccountHolder(body.data().accountHolderName());
        } catch (BankClientException ex) {
            throw BankErrorMapper.toBusinessException(ex);
        } catch (ResourceAccessException ex) {
            // 네트워크 오류(타임아웃·연결 실패) — 일시 장애.
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

    @Override
    public AccountToken verify(String bankCode, String accountNumber, String holderName) {
        try {
            BankVerifyEnvelope body = bankRestClient.post()
                    .uri(VERIFY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "bank_code", bankCode,
                            "account_number", accountNumber,
                            "holder_name", holderName))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::translateError)
                    .body(BankVerifyEnvelope.class);
            if (body == null || body.data() == null || body.data().accountToken() == null) {
                throw BankErrorMapper.toBusinessException(new BankClientException(
                        "BANK5000", HttpStatus.INTERNAL_SERVER_ERROR, "Mock 은행 응답 본문이 비어있음"));
            }
            return new AccountToken(body.data().accountToken());
        } catch (BankClientException ex) {
            throw BankErrorMapper.toBusinessException(ex);
        } catch (ResourceAccessException ex) {
            // 네트워크 오류(타임아웃·연결 실패) — 일시 장애.
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

    /**
     * 충전 출금(외부 계좌 차감). 본체가 받은 {@code idempotencyKey}를 Mock 은행에 그대로 forward한다 —
     * Mock 은행도 같은 키로 첫 응답을 재반환하므로, 본체에서 race로 두 번째 호출이 일어나도 Mock은
     * 동일 결과를 돌려준다(§13-1). 금액은 string 십진수로 보낸다.
     *
     * <p>에러 매핑은 {@link #inquiry}/{@link #verify}와 동일 경로 — Mock의 {@code BANK####}는
     * {@link #translateError} → {@link BankErrorMapper}(§13-4 매핑 SSOT)가 본체 도메인 에러로 변환한다.
     */
    @Override
    public WithdrawalResult withdraw(String accountToken, BigDecimal amount,
                                     String currencyCode, String idempotencyKey) {
        try {
            BankWithdrawEnvelope body = bankRestClient.post()
                    .uri(WITHDRAW_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of(
                            "account_token", accountToken,
                            "amount", amount.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "currency_code", currencyCode))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::translateError)
                    .body(BankWithdrawEnvelope.class);
            if (body == null || body.data() == null || body.data().status() == null) {
                throw BankErrorMapper.toBusinessException(new BankClientException(
                        "BANK5000", HttpStatus.INTERNAL_SERVER_ERROR, "Mock 은행 응답 본문이 비어있음"));
            }
            BankWithdrawData data = body.data();
            return new WithdrawalResult(
                    data.transactionId(), data.status(), data.amount(),
                    data.currencyCode(), data.balanceAfter());
        } catch (BankClientException ex) {
            throw BankErrorMapper.toBusinessException(ex);
        } catch (ResourceAccessException ex) {
            // 네트워크 오류(타임아웃·연결 실패) — 일시 장애.
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

    /**
     * 현금화 지급(외부 계좌 증액). 환율은 본체가 환전 시점에 적용한 최종 외화 금액만 Mock에 넘긴다.
     * {@code idempotencyKey}를 헤더로 그대로 forward한다 — Mock 은행도 같은 키로 첫 응답을 재반환한다.
     *
     * <p>에러 매핑은 {@link #withdraw}와 동일 경로 — {@link #translateError} → {@link BankErrorMapper}.
     * (BANK4002→ACCOUNT4003, BANK4010→ACCOUNT4006, BANK4040→ACCOUNT4001, 그 외/네트워크→COMMON5031).
     */
    @Override
    public PayoutResult payout(String bankCode, String accountNumber, BigDecimal amount,
                               String currencyCode, String idempotencyKey) {
        try {
            BankPayoutEnvelope body = bankRestClient.post()
                    .uri(PAYOUT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of(
                            "bank_code", bankCode,
                            "account_number", accountNumber,
                            "amount", amount.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "currency_code", currencyCode))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::translateError)
                    .body(BankPayoutEnvelope.class);
            if (body == null || body.data() == null || body.data().status() == null) {
                throw BankErrorMapper.toBusinessException(new BankClientException(
                        "BANK5000", HttpStatus.INTERNAL_SERVER_ERROR, "Mock 은행 응답 본문이 비어있음"));
            }
            BankPayoutData data = body.data();
            return new PayoutResult(
                    data.transactionId(), data.status(), data.amount(),
                    data.currencyCode(), data.balanceAfter());
        } catch (BankClientException ex) {
            throw BankErrorMapper.toBusinessException(ex);
        } catch (ResourceAccessException ex) {
            // 네트워크 오류(타임아웃·연결 실패) — 일시 장애.
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

    /**
     * 4xx/5xx 응답 본문에서 Mock 은행 {@code code}를 꺼내 {@link BankClientException}으로 던진다.
     * 본문 파싱 실패 시 합성 코드({@code BANK5000})로 폴백하되 {@code parseEx}는 cause로 보존한다.
     *
     * <p>{@link HttpStatus#resolve(int)}로 상태를 변환해 비표준 코드(418/451 등)가 와도 NPE/예외 없이
     * {@link HttpStatus#INTERNAL_SERVER_ERROR}로 폴백한다 — {@link HttpStatus#valueOf(int)}는
     * 비표준 코드에서 IllegalArgumentException을 던져 에러 핸들러 자체가 깨질 수 있다.
     */
    private void translateError(HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode statusCode = response.getStatusCode();
        HttpStatus resolvedStatus = HttpStatus.resolve(statusCode.value());
        if (resolvedStatus == null) {
            resolvedStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        BankErrorBody errorBody;
        try {
            errorBody = objectMapper.readValue(response.getBody(), BankErrorBody.class);
        } catch (Exception parseEx) {
            throw new BankClientException("BANK5000", resolvedStatus,
                    "Mock 은행 에러 응답 파싱 실패", parseEx);
        }
        throw new BankClientException(
                errorBody.code() != null ? errorBody.code() : "BANK5000",
                resolvedStatus,
                errorBody.message() != null ? errorBody.message() : "Mock 은행 에러");
    }

    /**
     * Mock 은행 inquiry 응답의 wire-format. 성공 응답은 {@code {success, data, message}} envelope로
     * 감싸여 오므로 {@code data} 한 단계를 거쳐 페이로드를 꺼낸다(에러 응답은 평탄해서 {@link BankErrorBody}로 별도 파싱).
     *
     * <p>{@code @JsonProperty}로 명시 매핑한다 — 어댑터 경계의 외부 계약은 전역 Jackson 설정
     * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE})에 의존하지 않는다.
     * 전역 설정이 바뀌어도 어댑터 동작은 동일해야 한다.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}로 명시한 이유: Mock 은행 응답에는 우리가
     * 쓰지 않는 필드(success/message/currency_code/...)가 함께 오는데, 이를 어댑터 record에
     * 일일이 선언하지 않아도 매핑이 깨지지 않게 한다. 운영 Spring Boot ObjectMapper는
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}가 기본이라 어차피 통과하지만, 어댑터 계약은
     * 전역 Jackson 설정에 의존하지 않아야 한다는 본 클래스의 규칙(클래스 javadoc) 그대로
     * 어노테이션으로 명시한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankInquiryEnvelope(
            @JsonProperty("data") BankInquiryData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankInquiryData(
            @JsonProperty("account_holder_name") String accountHolderName) {
    }

    /**
     * Mock 은행 verify 응답의 wire-format. envelope 구조와 어노테이션 규칙은
     * {@link BankInquiryEnvelope} 주석 참고.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankVerifyEnvelope(
            @JsonProperty("data") BankVerifyData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankVerifyData(
            @JsonProperty("account_token") String accountToken) {
    }

    /**
     * Mock 은행 withdrawal 응답의 wire-format. envelope 구조와 어노테이션 규칙은
     * {@link BankInquiryEnvelope} 주석 참고. {@code amount}/{@code balance_after}는 string 십진수로
     * 오지만 record 필드를 {@link BigDecimal}로 두면 Jackson이 자동 변환한다.
     *
     * <p>{@code balanceAfter}는 외부 계좌 잔액일 뿐 본체 지갑 잔액과 무관하다(§13-2). 본체는 이 값을
     * 응답에 노출하지 않는다 — 매핑만 해두고 사용하지 않는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankWithdrawEnvelope(
            @JsonProperty("data") BankWithdrawData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankWithdrawData(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("status") String status,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency_code") String currencyCode,
            @JsonProperty("balance_after") BigDecimal balanceAfter) {
    }

    /**
     * Mock 은행 payout 응답의 wire-format. envelope 구조와 어노테이션 규칙은
     * {@link BankInquiryEnvelope} 주석 참고. 필드 구성은 {@link BankWithdrawData}와 동일하다
     * (외부계좌 차감/증액 응답 모양이 같음 — {@code balance_after}는 외부 계좌 잔액).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankPayoutEnvelope(
            @JsonProperty("data") BankPayoutData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankPayoutData(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("status") String status,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency_code") String currencyCode,
            @JsonProperty("balance_after") BigDecimal balanceAfter) {
    }

    /** Mock 은행 에러 응답 본문. envelope 없이 평탄 — {@code success} 필드는 무시. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankErrorBody(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {
    }
}
