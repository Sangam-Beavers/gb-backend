package com.gb.wallet.domain.account.dto.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.wallet.domain.transaction.entity.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 충전 실행 응답. 명세 §12 응답 표와 1:1로 맞춘다.
 *
 * <p>금액({@code amount}/{@code walletBalance})은 응답 규약에 따라 string으로 전송하며 항상 소수 4자리
 * ({@code DECIMAL(18,4)})로 패딩한다(잔액 조회 DTO와 동일 패턴). 식별자는 {@code public_id}만 노출하고
 * 내부 {@code id}는 절대 싣지 않는다(CLAUDE.md §5). 시각은 ISO 8601 UTC {@code Z} 문자열이다.
 *
 * <p><b>Jackson 역직렬화:</b> 충전 멱등성 Layer 1(Redis 캐시)이 응답을 JSON으로 저장했다가 동일 키 재요청 시
 * 다시 객체로 복원한다. record인 {@code TransferExecuteResponse}와 달리 이 클래스는 {@code @Builder} private
 * 생성자라 Jackson이 creator를 추론하기 모호하므로, 생성자에 {@code @JsonCreator} + 직렬화 키와 1:1인 snake_case
 * {@code @JsonProperty}를 명시한다. 이로써 {@code -parameters}/{@code ParameterNamesModule} 유무와 무관하게
 * 같은 ObjectMapper로 round-trip이 보장된다. 직렬화는 기존대로 필드 기반(전역 SNAKE_CASE 전략)이라 출력은 불변.
 */
@Getter
public class ChargeResponse {

    @Schema(description = "충전 거래 식별자(UUID)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
    private final String publicId;

    @Schema(description = "출금 계좌 식별자(UUID, 요청 path의 id와 동일)",
            example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
    private final String accountPublicId;

    @Schema(description = "충전 금액(string, 소수 4자리)", example = "1530000.0000")
    private final String amount;

    @Schema(description = "통화 코드(KRW 고정)", example = "KRW")
    private final String currencyCode;

    @Schema(description = "충전 후 지갑 잔액(string, 소수 4자리)", example = "5430000.0000")
    private final String walletBalance;

    @Schema(description = "거래 상태", example = "COMPLETED")
    private final String status;

    @Schema(description = "거래 생성 시각(ISO 8601, UTC Z)", example = "2026-05-30T04:15:30Z")
    private final String createdAt;

    @Builder
    @JsonCreator
    private ChargeResponse(
            @JsonProperty("public_id") String publicId,
            @JsonProperty("account_public_id") String accountPublicId,
            @JsonProperty("amount") String amount,
            @JsonProperty("currency_code") String currencyCode,
            @JsonProperty("wallet_balance") String walletBalance,
            @JsonProperty("status") String status,
            @JsonProperty("created_at") String createdAt) {
        this.publicId = publicId;
        this.accountPublicId = accountPublicId;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.walletBalance = walletBalance;
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * 충전 거래와 처리 후 잔액으로 응답을 만든다.
     *
     * @param tx              저장된 충전 거래(public_id/amount/currency/status/created_at의 SSOT)
     * @param accountPublicId 요청 path의 출금 계좌 UUID(거래에는 내부 id만 들고 있어 응답엔 받은 값을 그대로 echo)
     * @param afterBalance    충전 처리 후 지갑 KRW 잔액(= audit_log의 after_balance)
     */
    public static ChargeResponse of(Transaction tx, String accountPublicId, BigDecimal afterBalance) {
        return ChargeResponse.builder()
                .publicId(tx.getPublicId())
                .accountPublicId(accountPublicId)
                .amount(tx.getAmount().setScale(4).toPlainString())
                .currencyCode(tx.getCurrencyCode().name())
                .walletBalance(afterBalance.setScale(4).toPlainString())
                .status(tx.getStatus().name())
                .createdAt(toUtcZ(tx.getCreatedAt()))
                .build();
    }

    /**
     * LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다(초 단위 절삭).
     * AccountResponse.toUtcZ와 동일 포맷 — 같은 도메인 소수 위치라 유틸로 추출하지 않는다(과도한 추상화 방지).
     */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}