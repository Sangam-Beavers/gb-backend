package com.gb.wallet.domain.transaction.dto.response;

import com.gb.wallet.domain.transaction.entity.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * POST /api/v1/transfers 응답 data(송금 실행 결과).
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환 — 필드는 camelCase로 두고
 * {@code @JsonProperty}를 붙이지 않는다. record를 쓰는 이유: 멱등성 Layer 1(Redis 캐시)에서
 * Jackson이 양방향(직렬화/역직렬화) 모두 다뤄야 하는데, record는 canonical constructor로 역직렬화가
 * 깔끔히 동작한다 — 일반 클래스+private 생성자보다 케이스가 적다.
 *
 * <p>금액·환율은 명세상 string 십진수로 직렬화한다(잔액 조회 패턴 동일). {@link #from(Transaction)}이
 * BigDecimal → string 변환(scale 4, HALF_UP)을 담당.
 *
 * <p>1단계(같은 통화) 범위에서 {@code exchangeRate}는 항상 {@code null}이며, {@code receiveAmount}는
 * {@code amount}와 동일하다.
 */
@Schema(description = "송금 실행 결과")
public record TransferExecuteResponse(

        @Schema(description = "거래 식별자(UUID, public_id)", example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
        String publicId,

        @Schema(description = "송금 방식", example = "INTERNAL_TRANSFER",
                allowableValues = {"INTERNAL_TRANSFER", "REMITTANCE"})
        String transferType,

        @Schema(description = "송금 금액 (string 십진수, 소수점 4자리)", example = "10000.0000")
        String amount,

        @Schema(description = "송금 통화 코드", example = "KRW")
        String currencyCode,

        @Schema(description = "송금 수수료 (string 십진수, 소수점 4자리)", example = "0.0000")
        String fee,

        @Schema(description = "적용 환율 (1단계 같은 통화는 항상 null)", example = "null", nullable = true)
        String exchangeRate,

        @Schema(description = "수취 금액 (1단계는 amount와 동일, string 십진수)", example = "10000.0000")
        String receiveAmount,

        @Schema(description = "수취 통화 코드", example = "KRW")
        String receiveCurrencyCode,

        @Schema(description = "거래 상태", example = "COMPLETED")
        String status,

        @Schema(description = "거래 생성 시각(ISO 8601, UTC Z)", example = "2026-06-01T03:15:30Z")
        String createdAt
) {

    /** Persisted {@link Transaction} → 응답 DTO. BigDecimal → string(소수 4자리), LocalDateTime → ISO Z. */
    public static TransferExecuteResponse from(Transaction tx) {
        return new TransferExecuteResponse(
                tx.getPublicId(),
                tx.getType().name(),
                scaledString(tx.getAmount()),
                tx.getCurrencyCode().name(),
                scaledString(tx.getFee()),
                tx.getExchangeRate() != null
                        ? tx.getExchangeRate().setScale(8, RoundingMode.HALF_UP).toPlainString()
                        : null,
                scaledString(tx.getReceiveAmount()),
                tx.getReceiveCurrencyCode() != null ? tx.getReceiveCurrencyCode().name() : null,
                tx.getStatus().name(),
                toUtcZ(tx.getCreatedAt())
        );
    }

    private static String scaledString(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /** LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환. 다른 응답 DTO와 동일 규칙. */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
