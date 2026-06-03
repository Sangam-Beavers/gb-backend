package com.gb.wallet.domain.transaction.scheduled.dto.response;

import com.gb.wallet.domain.transaction.scheduled.entity.ScheduledTransfer;
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
 * GET /api/v1/transfers/scheduled 응답 data — 정기 송금 목록(페이지).
 *
 * <p>배열 키 이름은 도메인 복수명({@code scheduled_transfers})으로 둔다 (Spring Data Page의 기본 {@code content}가
 * 아닌 명세 SSOT 따름). 페이지 메타 4필드(page/size/total_elements/total_pages)는 그대로 유지.
 */
@Schema(description = "정기 송금 목록 응답 (페이지)")
public record ScheduledTransferListResponse(

        @Schema(description = "정기 송금 목록")
        List<ScheduledTransferListItem> scheduledTransfers,

        @Schema(description = "현재 페이지 번호 (0-base)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 건수", example = "2")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "1")
        int totalPages
) {

    /** Spring Data {@link Page} → 응답 DTO. */
    public static ScheduledTransferListResponse from(Page<ScheduledTransfer> page) {
        List<ScheduledTransferListItem> items = page.getContent().stream()
                .map(ScheduledTransferListItem::from)
                .toList();
        return new ScheduledTransferListResponse(
                items,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    /**
     * 정기 송금 목록 한 항목. 명세 §7-2-3 응답 필드 그대로 (10개).
     * 설정 응답({@link ScheduledTransferResponse})과 달리 {@code transfer_type}/{@code bank_name}/{@code account_number}는
     * 포함하지 않는다 — 명세 SSOT 따름. 수신자 식별은 {@code receiver_name} snapshot만으로.
     */
    @Schema(description = "정기 송금 목록 항목")
    public record ScheduledTransferListItem(

            @Schema(description = "정기 송금 식별자(UUID)", example = "2b3c4d5e-1234-5678-90ab-cdef12345678")
            String publicId,

            @Schema(description = "수취인명 (설정 시 snapshot)", example = "NGUYEN VAN A", nullable = true)
            String receiverName,

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

        public static ScheduledTransferListItem from(ScheduledTransfer s) {
            return new ScheduledTransferListItem(
                    s.getPublicId(),
                    s.getReceiverName(),
                    scaledString(s.getAmount()),
                    s.getCurrencyCode().name(),
                    s.getReceiveCurrencyCode().name(),
                    s.getFrequency().name(),
                    s.getScheduleDay(),
                    s.getNextRunDate().toString(),
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
}
