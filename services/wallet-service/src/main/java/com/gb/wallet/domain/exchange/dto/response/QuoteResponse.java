package com.gb.wallet.domain.exchange.dto.response;

import com.gb.wallet.domain.exchange.dto.QuoteData;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 환전 견적 응답. 금액·환율은 응답 표기상 string(소수 4자리)으로 전송한다(환율 내부 정밀도는 8자리).
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
     * 환율·금액 모두 응답 표기는 소수 4자리로 패딩한다(환율 내부 정밀도 8자리 → 응답 4자리, 다른 응답 DTO와 동일 패턴).
     */
    public static QuoteResponse of(QuoteData quote, Instant expiresAt) {
        return QuoteResponse.builder()
                .quotePublicId(quote.quotePublicId())
                // scale 축소 시 RoundingMode 없으면 ArithmeticException — 명시적 HALF_UP.
                .exchangeRate(quote.exchangeRate().setScale(4, RoundingMode.HALF_UP).toPlainString())
                .fee(quote.fee().setScale(4, RoundingMode.HALF_UP).toPlainString())
                .feeCurrencyCode(quote.feeCurrencyCode().name())
                .receiveAmount(quote.receiveAmount().setScale(4, RoundingMode.HALF_UP).toPlainString())
                .receiveCurrencyCode(quote.receiveCurrencyCode().name())
                .expiresAt(DateTimeFormatter.ISO_INSTANT.format(expiresAt.truncatedTo(ChronoUnit.SECONDS)))
                .build();
    }
}
