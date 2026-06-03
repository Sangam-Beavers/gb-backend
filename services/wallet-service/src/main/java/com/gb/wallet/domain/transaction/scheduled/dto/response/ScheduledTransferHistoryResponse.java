package com.gb.wallet.domain.transaction.scheduled.dto.response;

import com.gb.wallet.domain.transaction.entity.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * GET /api/v1/transfers/scheduled/{id}/history 응답 data — 특정 정기송금의 회차별 실행 이력(페이지).
 *
 * <p>회차 거래는 {@code transactions} 테이블의 송금 거래 중 {@code idempotency_key}가
 * {@code "scheduled:{publicId}:"} prefix로 시작하는 행이다(스케줄러 회차마다 그 형태로 박힘).
 *
 * <p>배열 키 이름은 명세 SSOT 따라 {@code histories} (Spring Page의 기본 {@code content} 아님).
 */
@Schema(description = "정기 송금 회차 실행 이력 (페이지)")
public record ScheduledTransferHistoryResponse(

        @Schema(description = "회차 실행 이력 목록")
        List<ScheduledTransferHistoryItem> histories,

        @Schema(description = "현재 페이지 번호 (0-base)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 건수", example = "3")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "1")
        int totalPages
) {

    public static ScheduledTransferHistoryResponse from(Page<Transaction> page) {
        List<ScheduledTransferHistoryItem> items = page.getContent().stream()
                .map(ScheduledTransferHistoryItem::from)
                .toList();
        return new ScheduledTransferHistoryResponse(
                items,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    /**
     * 회차 이력 한 항목. 명세 §7-2-5 응답 필드 그대로 (8개).
     *
     * <p>현재 단계에선 {@code status}는 항상 {@code COMPLETED}로만 응답된다 — 송금 실패 시 메인
     * 트랜잭션 rollback으로 transactions INSERT 자체가 일어나지 않기 때문(FAILED 흔적은
     * {@code remittance_attempts}에만). 향후 FAILED 상태 저장 도입 시 자연스럽게 노출된다.
     */
    @Schema(description = "정기 송금 회차 실행 이력 항목")
    public record ScheduledTransferHistoryItem(

            @Schema(description = "회차 거래 식별자(UUID)", example = "c1d2e3f4-5678-90ab-cdef-1234567890ab")
            String publicId,

            @Schema(description = "송금 금액 (string 십진수)", example = "500000.0000")
            String amount,

            @Schema(description = "출금 통화 코드", example = "KRW")
            String currencyCode,

            @Schema(description = "수수료 (string 십진수)", example = "3000.0000")
            String fee,

            @Schema(description = "수취 금액 (string 십진수)", example = "500000.0000")
            String receiveAmount,

            @Schema(description = "수취 통화 코드", example = "KRW")
            String receiveCurrencyCode,

            @Schema(description = "거래 상태 (현 단계는 COMPLETED만)", example = "COMPLETED",
                    allowableValues = {"COMPLETED", "FAILED"})
            String status,

            @Schema(description = "실행 시각 (ISO 8601 UTC Z) — transactions.created_at",
                    example = "2026-05-25T12:00:00Z")
            String executedAt
    ) {

        public static ScheduledTransferHistoryItem from(Transaction tx) {
            return new ScheduledTransferHistoryItem(
                    tx.getPublicId(),
                    scaledString(tx.getAmount()),
                    tx.getCurrencyCode().name(),
                    scaledString(tx.getFee()),
                    scaledString(tx.getReceiveAmount()),
                    tx.getReceiveCurrencyCode() != null ? tx.getReceiveCurrencyCode().name() : null,
                    tx.getStatus().name(),
                    toUtcZ(tx.getCreatedAt())
            );
        }

        private static String scaledString(BigDecimal value) {
            if (value == null) return null;
            return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
        }

        private static String toUtcZ(LocalDateTime dateTime) {
            if (dateTime == null) return null;
            return DateTimeFormatter.ISO_INSTANT.format(
                    dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
        }
    }
}
