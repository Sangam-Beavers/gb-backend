package com.gb.wallet.global.client;

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
     * Mock 은행 inquiry 응답. 필드명은 camelCase로 두고 전역 Jackson 설정
     * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE})이 {@code account_holder_name}으로 매핑한다.
     */
    private record BankInquiryResponse(String accountHolderName) {
    }

    /** Mock 은행 에러 응답 본문(단일어 키라 스네이크 변환 영향 없음). */
    private record BankErrorBody(String code, String message) {
    }
}
