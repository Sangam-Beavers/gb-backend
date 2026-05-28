package com.gb.wallet.global.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import java.io.IOException;
import java.math.BigDecimal;
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
 * <p>이번 PR에서는 {@link #inquiry}만 실동작하고, {@code verify}/{@code withdraw}/{@code payout}은
 * 후속 이슈에서 구현된다(인터페이스 시그니처는 동결).
 *
 * <p>운영 전환 시 {@code RealBankClient}(@Profile("prod"))가 추가되며 Service 코드는 그대로 둔다.
 *
 * <p><b>어댑터 경계 규칙:</b> 외부 시스템과의 wire-format 매핑(아래 private record)은
 * {@code @JsonProperty}로 <em>명시</em>한다. 본체 응답 DTO와 달리 전역 Jackson 설정
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE})에 의존하지 않는다 —
 * 외부 계약은 전역 설정 변경에 영향받지 않아야 한다. 후속 PR에서 {@code verify}/{@code withdraw}/
 * {@code payout}용 wire-format record를 추가할 때도 동일 패턴을 따른다.
 */
@Component
@Profile({"dev", "stage"})
public class MockBankClient implements BankClient {

    private static final String INQUIRY_PATH = "/api/v1/bank/accounts/inquiry";

    private final RestClient bankRestClient;
    private final ObjectMapper objectMapper;

    public MockBankClient(RestClient bankRestClient, ObjectMapper objectMapper) {
        this.bankRestClient = bankRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AccountHolder inquiry(String bankCode, String accountNumber) {
        try {
            BankInquiryResponse body = bankRestClient.post()
                    .uri(INQUIRY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "bank_code", bankCode,
                            "account_number", accountNumber))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::translateError)
                    .body(BankInquiryResponse.class);
            if (body == null || body.accountHolderName() == null) {
                throw BankErrorMapper.toBusinessException(new BankClientException(
                        "BANK5000", HttpStatus.INTERNAL_SERVER_ERROR, "Mock 은행 응답 본문이 비어있음"));
            }
            return new AccountHolder(body.accountHolderName());
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
        throw new UnsupportedOperationException("후속 이슈에서 구현 — 계좌 인증/토큰 발급");
    }

    @Override
    public WithdrawalResult withdraw(String accountToken, BigDecimal amount,
                                     String currencyCode, String idempotencyKey) {
        throw new UnsupportedOperationException("후속 이슈에서 구현 — 충전 출금");
    }

    @Override
    public PayoutResult payout(String bankCode, String accountNumber, BigDecimal amount,
                               String currencyCode, String idempotencyKey) {
        throw new UnsupportedOperationException("후속 이슈에서 구현 — 현금화 지급");
    }

    /**
     * 4xx/5xx 응답 본문에서 Mock 은행 {@code code}를 꺼내 {@link BankClientException}으로 던진다.
     * 본문 파싱 실패 시 합성 코드({@code BANK5000})로 폴백.
     */
    private void translateError(HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        BankErrorBody errorBody;
        try {
            errorBody = objectMapper.readValue(response.getBody(), BankErrorBody.class);
        } catch (Exception parseEx) {
            throw new BankClientException("BANK5000", HttpStatus.valueOf(status.value()),
                    "Mock 은행 에러 응답 파싱 실패");
        }
        throw new BankClientException(
                errorBody.code() != null ? errorBody.code() : "BANK5000",
                HttpStatus.valueOf(status.value()),
                errorBody.message() != null ? errorBody.message() : "Mock 은행 에러");
    }

    /**
     * Mock 은행 inquiry 응답의 wire-format.
     *
     * <p>{@code @JsonProperty}로 명시 매핑한다 — 어댑터 경계의 외부 계약은 전역 Jackson 설정
     * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE})에 의존하지 않는다.
     * 전역 설정이 바뀌어도 어댑터 동작은 동일해야 한다.
     */
    private record BankInquiryResponse(
            @JsonProperty("account_holder_name") String accountHolderName) {
    }

    /** Mock 은행 에러 응답 본문. 단일어 키지만 일관성 위해 명시. */
    private record BankErrorBody(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {
    }
}
