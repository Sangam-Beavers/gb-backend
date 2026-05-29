package com.gb.wallet.domain.transaction.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/v1/transfers/recent-accounts 응답 data.
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환된다
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE}).
 *
 * <p>금액은 명세에 따라 소수점 4자리 string으로, 시각은 ISO 8601 UTC Z 문자열로 직렬화된다.
 */
@Getter
public class RecentAccountsResponse {

    @Schema(description = "최근 송금 계좌 목록 (계좌별 최신 송금 1건씩, 최근순)")
    private final List<AccountItem> accounts;

    private RecentAccountsResponse(List<AccountItem> accounts) {
        this.accounts = accounts;
    }

    public static RecentAccountsResponse of(List<AccountItem> accounts) {
        return new RecentAccountsResponse(accounts);
    }

    /**
     * LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다. 초 단위 절삭.
     * 다른 응답 DTO(잔액/최근 송금 사용자)와 동일한 규칙.
     */
    public static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Getter
    public static class AccountItem {

        @Schema(description = "은행 코드", example = "KOOKMIN")
        private final String bankCode;

        @Schema(description = "은행명", example = "국민은행")
        private final String bankName;

        @Schema(description = "마스킹된 계좌번호 (앞 3 + -****- + 뒤 4)", example = "123-****-1111")
        private final String accountNumber;

        @Schema(description = "수취인명 (송금 시점의 transactions.receiver_name)", example = "김민수")
        private final String accountHolder;

        @Schema(description = "가장 최근 송금의 통화 코드", example = "KRW",
                allowableValues = {"KRW", "USD", "PHP", "VND"})
        private final String currencyCode;

        @Schema(description = "가장 최근 송금 금액 (소수점 4자리 string)", example = "200000.0000", type = "string")
        private final String lastAmount;

        @Schema(description = "가장 최근 송금 시각(ISO 8601, UTC Z)", example = "2026-05-24T09:20:00Z")
        private final String lastTransferredAt;

        @Builder
        private AccountItem(String bankCode, String bankName, String accountNumber, String accountHolder,
                            String currencyCode, String lastAmount, String lastTransferredAt) {
            this.bankCode = bankCode;
            this.bankName = bankName;
            this.accountNumber = accountNumber;
            this.accountHolder = accountHolder;
            this.currencyCode = currencyCode;
            this.lastAmount = lastAmount;
            this.lastTransferredAt = lastTransferredAt;
        }
    }
}
