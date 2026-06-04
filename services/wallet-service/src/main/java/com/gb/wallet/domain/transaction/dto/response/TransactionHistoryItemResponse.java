package com.gb.wallet.domain.transaction.dto.response;

import com.gb.wallet.domain.transaction.entity.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 전자지갑 거래내역 단건 항목({@code GET /api/v1/wallets/me/transactions}). 모든 유형(CHARGE/
 * INTERNAL_TRANSFER/REMITTANCE/EXCHANGE)의 거래를 하나의 항목 형태로 표현한다. 금액은 string(소수 4자리),
 * 식별자는 public_id(UUID), 시각은 ISO 8601 UTC Z로 직렬화한다({@code ExchangeResponse}와 동일 변환 규칙).
 *
 * <p>환전 멱등성 캐시처럼 응답을 JSON으로 저장했다가 복원하는 round-trip이 없으므로(거래내역은 캐시하지
 * 않는다 — CLAUDE.md §8) {@code @JsonCreator}/{@code @JsonProperty} 없이 일반 {@code @Getter} +
 * 정적 {@code from}만 둔다. 직렬화는 전역 SNAKE_CASE 전략에 위임한다.
 *
 * <p>TODO: 응답 필드는 {@link Transaction} 엔티티 기반 잠정안이다. 팀 API 명세서(거래내역 화면 와이어프레임)
 *       확정 시 1:1 정합 검토 — 유형별 노출 필드/마스킹/추가 메타가 바뀔 수 있다.
 */
@Getter
public class TransactionHistoryItemResponse {

    @Schema(description = "거래 식별자(UUID)", example = "1a2b3c4d-5678-90ab-cdef-012345678901")
    private final String publicId;

    @Schema(description = "거래 유형", example = "CHARGE",
            allowableValues = {"CHARGE", "INTERNAL_TRANSFER", "REMITTANCE", "EXCHANGE"})
    private final String type;

    @Schema(description = "거래 상태", example = "COMPLETED",
            allowableValues = {"PENDING", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"})
    private final String status;

    @Schema(description = "거래(출금) 금액 (string, 소수 4자리)", example = "500000.0000")
    private final String amount;

    @Schema(description = "출금 통화 코드", example = "KRW")
    private final String currencyCode;

    @Schema(description = "수수료 (string, 소수 4자리)", example = "0.0000")
    private final String fee;

    @Schema(description = "수령액 (string, 환전·송금만, nullable)", example = "72.4500", nullable = true)
    private final String receiveAmount;

    @Schema(description = "수령 통화 코드 (환전·송금만, nullable)", example = "USD", nullable = true)
    private final String receiveCurrencyCode;

    @Schema(description = "수취인 이름 (송금만, nullable)", example = "홍길동", nullable = true)
    private final String receiverName;

    @Schema(description = "거래 시각 (ISO 8601 UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String createdAt;

    @Builder
    private TransactionHistoryItemResponse(String publicId, String type, String status, String amount,
                                           String currencyCode, String fee, String receiveAmount,
                                           String receiveCurrencyCode, String receiverName, String createdAt) {
        this.publicId = publicId;
        this.type = type;
        this.status = status;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.fee = fee;
        this.receiveAmount = receiveAmount;
        this.receiveCurrencyCode = receiveCurrencyCode;
        this.receiverName = receiverName;
        this.createdAt = createdAt;
    }

    public static TransactionHistoryItemResponse from(Transaction tx) {
        return TransactionHistoryItemResponse.builder()
                .publicId(tx.getPublicId())
                .type(tx.getType().name())
                .status(tx.getStatus().name())
                .amount(toPlainString(tx.getAmount()))
                .currencyCode(tx.getCurrencyCode().name())
                .fee(toPlainString(tx.getFee()))
                .receiveAmount(toPlainString(tx.getReceiveAmount()))
                .receiveCurrencyCode(tx.getReceiveCurrencyCode() != null ? tx.getReceiveCurrencyCode().name() : null)
                .receiverName(tx.getReceiverName())
                .createdAt(toUtcZ(tx.getCreatedAt()))
                .build();
    }

    /** 금액을 소수 4자리 string으로 변환한다(지수 표기 회피). null이면 그대로 null. */
    private static String toPlainString(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /** LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다(초 단위 절삭). */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
