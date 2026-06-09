package com.gb.wallet.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.client.dto.VerifyInitResult;
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
 * 개발/스테이지용 {@link BankClient} 구현. 외부 Mock 은행 서버를 호출한다.
 *
 * <p><b>어댑터 경계 규칙:</b> wire-format 매핑(아래 private record)은 {@code @JsonProperty}로 명시한다.
 * 전역 Jackson 설정({@code spring.jackson.property-naming-strategy: SNAKE_CASE})에 의존하지 않는다 —
 * 외부 계약은 전역 설정 변경에 영향받지 않아야 한다.
 */
@Component
@Profile({"dev", "stage"})
public class MockBankClient implements BankClient {

    private static final String INQUIRY_PATH  = "/api/v1/bank/accounts/inquiry";
    private static final String VERIFY_PATH   = "/api/v1/bank/accounts/verify";
    private static final String CONFIRM_PATH  = "/api/v1/bank/accounts/confirm";
    private static final String WITHDRAW_PATH = "/api/v1/bank/transfers/withdrawal";
    private static final String PAYOUT_PATH   = "/api/v1/bank/transfers/payout";

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
                    .body(Map.of("bank_code", bankCode, "account_number", accountNumber))
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
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

    /**
     * 1원 소액이체 인증 요청. 해당 계좌에 1원 입금 + 적요에 4자리 인증번호 기재.
     * account_token은 발급하지 않으며, {@link #confirmVerify}에서 코드 검증 후 발급한다.
     */
    @Override
    public VerifyInitResult initVerify(String bankCode, String accountNumber, String holderName) {
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
            if (body == null || body.data() == null || !body.data().pending()) {
                throw BankErrorMapper.toBusinessException(new BankClientException(
                        "BANK5000", HttpStatus.INTERNAL_SERVER_ERROR, "Mock 은행 응답 본문이 비어있음"));
            }
            return new VerifyInitResult(body.data().pending(), body.data().expiresAt());
        } catch (BankClientException ex) {
            throw BankErrorMapper.toBusinessException(ex);
        } catch (ResourceAccessException ex) {
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

    /**
     * 인증번호 확인 + account_token 발급.
     * 코드 불일치 → BANK4005(→ ACCOUNT4008), 세션 없음/만료 → BANK4006(→ ACCOUNT4009).
     */
    @Override
    public AccountToken confirmVerify(String bankCode, String accountNumber, String code) {
        try {
            BankConfirmEnvelope body = bankRestClient.post()
                    .uri(CONFIRM_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "bank_code", bankCode,
                            "account_number", accountNumber,
                            "code", code))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::translateError)
                    .body(BankConfirmEnvelope.class);
            if (body == null || body.data() == null || body.data().accountToken() == null) {
                throw BankErrorMapper.toBusinessException(new BankClientException(
                        "BANK5000", HttpStatus.INTERNAL_SERVER_ERROR, "Mock 은행 응답 본문이 비어있음"));
            }
            return new AccountToken(body.data().accountToken());
        } catch (BankClientException ex) {
            throw BankErrorMapper.toBusinessException(ex);
        } catch (ResourceAccessException ex) {
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

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
            BankWithdrawData d = body.data();
            return new WithdrawalResult(d.transactionId(), d.status(), d.amount(), d.currencyCode(), d.balanceAfter());
        } catch (BankClientException ex) {
            throw BankErrorMapper.toBusinessException(ex);
        } catch (ResourceAccessException ex) {
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

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
            BankPayoutData d = body.data();
            return new PayoutResult(d.transactionId(), d.status(), d.amount(), d.currencyCode(), d.balanceAfter());
        } catch (BankClientException ex) {
            throw BankErrorMapper.toBusinessException(ex);
        } catch (ResourceAccessException ex) {
            throw BankErrorMapper.toBusinessException(
                    new BankClientException(null, HttpStatus.SERVICE_UNAVAILABLE,
                            "Mock 은행 연결 실패: " + ex.getMessage(), ex));
        }
    }

    private void translateError(HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode statusCode = response.getStatusCode();
        HttpStatus resolvedStatus = HttpStatus.resolve(statusCode.value());
        if (resolvedStatus == null) resolvedStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        BankErrorBody errorBody;
        try {
            errorBody = objectMapper.readValue(response.getBody(), BankErrorBody.class);
        } catch (Exception parseEx) {
            throw new BankClientException("BANK5000", resolvedStatus, "Mock 은행 에러 응답 파싱 실패", parseEx);
        }
        throw new BankClientException(
                errorBody.code() != null ? errorBody.code() : "BANK5000",
                resolvedStatus,
                errorBody.message() != null ? errorBody.message() : "Mock 은행 에러");
    }

    // ── Wire-format records ────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankInquiryEnvelope(@JsonProperty("data") BankInquiryData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankInquiryData(@JsonProperty("account_holder_name") String accountHolderName) {}

    /** initVerify(POST /verify) 응답. 구 방식의 account_token 없이 pending 상태만 반환. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankVerifyEnvelope(@JsonProperty("data") BankVerifyData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankVerifyData(
            @JsonProperty("pending")     boolean pending,
            @JsonProperty("expires_at")  String  expiresAt) {}

    /** confirmVerify(POST /confirm) 응답. 코드 검증 성공 시 account_token 발급. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankConfirmEnvelope(@JsonProperty("data") BankConfirmData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankConfirmData(@JsonProperty("account_token") String accountToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankWithdrawEnvelope(@JsonProperty("data") BankWithdrawData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankWithdrawData(
            @JsonProperty("transaction_id") String     transactionId,
            @JsonProperty("status")          String     status,
            @JsonProperty("amount")          BigDecimal amount,
            @JsonProperty("currency_code")   String     currencyCode,
            @JsonProperty("balance_after")   BigDecimal balanceAfter) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankPayoutEnvelope(@JsonProperty("data") BankPayoutData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankPayoutData(
            @JsonProperty("transaction_id") String     transactionId,
            @JsonProperty("status")          String     status,
            @JsonProperty("amount")          BigDecimal amount,
            @JsonProperty("currency_code")   String     currencyCode,
            @JsonProperty("balance_after")   BigDecimal balanceAfter) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankErrorBody(
            @JsonProperty("code")    String code,
            @JsonProperty("message") String message) {}
}
