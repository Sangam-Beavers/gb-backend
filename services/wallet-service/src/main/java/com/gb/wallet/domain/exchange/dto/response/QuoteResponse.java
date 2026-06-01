package com.gb.wallet.domain.exchange.dto.response;

import com.gb.wallet.domain.exchange.dto.QuoteData;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 환전 견적 응답. 금액·환율은 명세 §0/§5에 따라 string(소수 4자리, 환율은 8자리)으로 전송한다.
 */
@Getter
public class QuoteResponse {

    @Schema(description = "견적 식별자(UUID, 환전 실행 시 사용)",
            example = "9f8e7d6c-1234-5678-abcd-ef0123456789")
    private final String quotePublicId;

    @Schema(description = "적용 환율 (string, \"1 외화→KRW\")", example = "1380.0000")
    private final String exchangeRate;

    @Schema(description = "수수료 (string, KRW 기준)", example = "1000.0000")
    private final String fee;

    @Schema(description = "수수료 통화 코드", example = "KRW")
    private final String feeCurrencyCode;

    @Schema(description = "예상 수령액 (string)", example = "72.4500")
    private final String receiveAmount;

    @Schema(description = "수령 통화 코드 (ISO 4217)", example = "USD")
    private final String receiveCurrencyCode;

    @Schema(description = "견적 만료 시각 (ISO 8601 UTC Z)", example = "2026-05-26T05:35:00Z")
    private final String expiresAt;

    @Builder
    private QuoteResponse(String quotePublicId, String exchangeRate, String fee, String feeCurrencyCode,
                          String receiveAmount, String receiveCurrencyCode, String expiresAt) {
        this.quotePublicId = quotePublicId;
        this.exchangeRate = exchangeRate;
        this.fee = fee;
        this.feeCurrencyCode = feeCurrencyCode;
        this.receiveAmount = receiveAmount;
        this.receiveCurrencyCode = receiveCurrencyCode;
        this.expiresAt = expiresAt;
    }

    /**
     * 견적 스냅샷 + 만료시각(Instant)으로 응답을 만든다.
     * 환율은 소수 8자리, 금액은 소수 4자리로 패딩(명세 §5, 다른 응답 DTO와 동일 패턴).
     */
    public static QuoteResponse of(QuoteData quote, Instant expiresAt) {
        return QuoteResponse.builder()
                .quotePublicId(quote.quotePublicId())
                .exchangeRate(quote.exchangeRate().setScale(4).toPlainString())
                .fee(quote.fee().setScale(4).toPlainString())
                .feeCurrencyCode(quote.feeCurrencyCode().name())
                .receiveAmount(quote.receiveAmount().setScale(4).toPlainString())
                .receiveCurrencyCode(quote.receiveCurrencyCode().name())
                .expiresAt(DateTimeFormatter.ISO_INSTANT.format(expiresAt.truncatedTo(ChronoUnit.SECONDS)))
                .build();
    }
}
