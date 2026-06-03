package com.gb.wallet.domain.transaction.scheduled.dto.response;

import com.gb.wallet.domain.transaction.scheduled.entity.ScheduledTransfer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * POST /api/v1/transfers/scheduled 응답 data — 정기 송금 설정 결과.
 *
 * <p>{@code nextRunDate}는 date만 (YYYY-MM-DD) — 시간 의미 없음. {@code createdAt}은 ISO 8601 UTC Z.
 *
 * <p>{@code transferType}을 응답에 포함해 INTERNAL/REMITTANCE 구분이 명확하게 한다(목록 조회·확인증과 일관).
 * REMITTANCE 한정 정보(은행명·계좌번호)는 본 응답에 포함하지 않는다 — 단건 조회/확인증 응답에서 풀어 본다.
 */
@Schema(description = "정기 송금 설정 결과")
public record ScheduledTransferResponse(

        @Schema(description = "정기 송금 식별자(UUID)", example = "2b3c4d5e-1234-5678-90ab-cdef12345678")
        String publicId,

        @Schema(description = "송금 방식", example = "REMITTANCE",
                allowableValues = {"INTERNAL_TRANSFER", "REMITTANCE"})
        String transferType,

        @Schema(description = "회차당 송금액 (string 십진수)", example = "500000.0000")
        String amount,

        @Schema(description = "출금 통화 코드", example = "KRW")
        String currencyCode,

        @Schema(description = "수취 통화 코드", example = "KRW")
        String receiveCurrencyCode,

        @Schema(description = "반복 주기", example = "MONTHLY",
                allowableValues = {"WEEKLY", "MONTHLY"})
        String frequency,

        @Schema(description = "실행 기준일 (MONTHLY=1~31, WEEKLY=1~7 ISO)", example = "25")
        int scheduleDay,

        @Schema(description = "다음 실행 예정일 (ISO 8601 date, KST 기준)", example = "2026-06-25")
        String nextRunDate,

        @Schema(description = "마지막 실행 시각 (ISO 8601 UTC Z). 최초 실행 전이면 null",
                example = "2026-05-25T16:00:00Z", nullable = true)
        String lastRunAt,

        @Schema(description = "상태", example = "ACTIVE",
                allowableValues = {"ACTIVE", "PAUSED", "CANCELLED"})
        String status,

        @Schema(description = "생성 시각 (ISO 8601 UTC Z)", example = "2026-05-25T13:00:00Z")
        String createdAt
) {

    public static ScheduledTransferResponse from(ScheduledTransfer s) {
        return new ScheduledTransferResponse(
                s.getPublicId(),
                s.getTransferType().name(),
                scaledString(s.getAmount()),
                s.getCurrencyCode().name(),
                s.getReceiveCurrencyCode().name(),
                s.getFrequency().name(),
                s.getScheduleDay(),
                s.getNextRunDate().toString(),  // ISO 8601 date (YYYY-MM-DD)
                toUtcZ(s.getLastRunAt()),       // null이면 toUtcZ가 null 반환
                s.getStatus().name(),
                toUtcZ(s.getCreatedAt())
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
