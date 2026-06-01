package com.gb.wallet.domain.exchange.dto.response;

import com.gb.wallet.domain.transaction.entity.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 환전 실행/내역 조회 응답. 명세 §9/§10 표와 1:1로 맞춘다. 실행(201)·내역 조회(200)가 동일 구조라 공용.
 *
 * <p>금액·환율은 string(금액 소수 4자리, 환율 소수 8자리 → 응답 표기상 4자리로 패딩)으로 전송한다.
 * 식별자는 public_id(UUID)만, 시각은 ISO 8601 UTC Z. transactions(type=EXCHANGE)에서 만든다.
 */
@Getter
public class ExchangeResponse {

    @Schema(description = "환전 내역 식별자(UUID)", example = "1a2b3c4d-5678-90ab-cdef-012345678901")
    private final String publicId;

    @Schema(description = "환전 유형 (EXCHANGE / RE_EXCHANGE)", example = "EXCHANGE")
    private final String exchangeType;

    @Schema(description = "출금 통화 코드", example = "KRW")
    private final String fromCurrencyCode;

    @Schema(description = "입금 통화 코드", example = "USD")
    private final String toCurrencyCode;

    @Schema(description = "환전 신청 금액 (string)", example = "100000.0000")
    private final String amount;

    @Schema(description = "적용 환율 (string, \"1 외화→KRW\")", example = "1380.0000")
    private final String exchangeRate;

    @Schema(description = "수수료 (string, KRW 기준)", example = "1000.0000")
    private final String fee;

    @Schema(description = "실제 수령액 (string)", example = "72.4500")
    private final String receiveAmount;

    @Schema(description = "수령 통화 코드", example = "USD")
    private final String receiveCurrencyCode;

    @Schema(description = "거래 상태", example = "COMPLETED")
    private final String status;

    @Schema(description = "환전 완료 시각 (ISO 8601 UTC Z)", example = "2026-05-26T05:30:00Z")
    private final String exchangedAt;

    @Builder
    private ExchangeResponse(String publicId, String exchangeType, String fromCurrencyCode,
                            String toCurrencyCode, String amount, String exchangeRate, String fee,
                            String receiveAmount, String receiveCurrencyCode, String status,
                            String exchangedAt) {
        this.publicId = publicId;
        this.exchangeType = exchangeType;
        this.fromCurrencyCode = fromCurrencyCode;
        this.toCurrencyCode = toCurrencyCode;
        this.amount = amount;
        this.exchangeRate = exchangeRate;
        this.fee = fee;
        this.receiveAmount = receiveAmount;
        this.receiveCurrencyCode = receiveCurrencyCode;
        this.status = status;
        this.exchangedAt = exchangedAt;
    }

    /**
     * 환전 거래(transactions, type=EXCHANGE)로부터 응답을 만든다.
     *
     * <p>거래의 컬럼 매핑: from 통화 = {@code currencyCode}(출금 통화), to 통화 = {@code receiveCurrencyCode},
     * 신청 금액 = {@code amount}, 수령액 = {@code receiveAmount} 또는 {@code toAmount}, 환율 = {@code exchangeRate}.
     *
     * @param tx           환전 거래
     * @param exchangeType 환전 유형(EXCHANGE/RE_EXCHANGE) — transactions에 별도 컬럼이 없어 호출 측에서 전달
     */
    public static ExchangeResponse from(Transaction tx, String exchangeType) {
        return ExchangeResponse.builder()
                .publicId(tx.getPublicId())
                .exchangeType(exchangeType)
                .fromCurrencyCode(tx.getCurrencyCode().name())
                .toCurrencyCode(tx.getReceiveCurrencyCode().name())
                .amount(tx.getAmount().setScale(4).toPlainString())
                .exchangeRate(tx.getExchangeRate().setScale(4).toPlainString())
                .fee(tx.getFee().setScale(4).toPlainString())
                .receiveAmount(tx.getReceiveAmount().setScale(4).toPlainString())
                .receiveCurrencyCode(tx.getReceiveCurrencyCode().name())
                .status(tx.getStatus().name())
                .exchangedAt(toUtcZ(tx.getCreatedAt()))
                .build();
    }

    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
