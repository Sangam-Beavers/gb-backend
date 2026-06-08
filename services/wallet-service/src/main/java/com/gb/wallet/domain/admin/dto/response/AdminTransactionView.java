package com.gb.wallet.domain.admin.dto.response;

import com.gb.wallet.domain.transaction.entity.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * /internal/admin 거래 단건 응답.
 *
 * <p>금액은 String 직렬화(conventions §0). 내부 BIGINT id는 노출 금지 — public_id 만 노출.
 * receiver는 INTERNAL_TRANSFER 만 채워진다(다른 type은 null).
 *
 * <p>risk_level은 wallet 본체 스키마에 컬럼이 없어 발표용 임시 정책으로 계산한다(amount 기반).
 * 본격적 risk scoring 도입 시 별도 컬럼/엔진으로 대체.
 */
@Schema(description = "관리자 거래 뷰(/internal/admin)")
public record AdminTransactionView(
        @Schema(example = "aaaaaaaa-0001-0000-0000-000000000001") String transactionPublicId,
        @Schema(example = "11111111-1111-1111-1111-111111111111") String userPublicId,
        @Schema(example = "INTERNAL_TRANSFER") String type,
        @Schema(example = "1200000.0000") String amount,
        @Schema(example = "VND") String currencyCode,
        @Schema(example = "COMPLETED") String status,
        @Schema(example = "LOW") String riskLevel,
        @Schema(example = "2026-06-07T10:12:00") LocalDateTime executedAt,
        @Schema(example = "33333333-3333-3333-3333-333333333333", nullable = true)
        String receiverUserPublicId
) {

    public static AdminTransactionView from(Transaction t) {
        String receiver = t.getReceiverWallet() != null ? t.getReceiverWallet().getUserPublicId() : null;
        return new AdminTransactionView(
                t.getPublicId(),
                t.getWallet().getUserPublicId(),
                t.getType().name(),
                t.getAmount() == null ? null : t.getAmount().toPlainString(),
                t.getCurrencyCode().name(),
                t.getStatus().name(),
                computeRiskLevel(t),
                t.getCreatedAt(),
                receiver
        );
    }

    /**
     * 발표용 임시 risk: KRW 기준 ≥ 5,000,000 → HIGH, ≥ 1,000,000 → MEDIUM, else LOW.
     * 통화별 환산은 안 한다(데모 단순화). 본격 도입 시 별도 엔진/컬럼으로 대체.
     */
    private static String computeRiskLevel(Transaction t) {
        if (t.getAmount() == null) {
            return "LOW";
        }
        double v = t.getAmount().doubleValue();
        if (v >= 5_000_000d) {
            return "HIGH";
        }
        if (v >= 1_000_000d) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
