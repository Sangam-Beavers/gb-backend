package com.gb.wallet.domain.wallet.dto.response;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/v1/wallets/me/balances 응답 data.
 *
 * <p>JSON 필드명은 <b>전역</b> Jackson 설정으로 변환된다
 * (application.yaml: {@code spring.jackson.property-naming-strategy: SNAKE_CASE}).
 * 따라서 필드는 camelCase로 두고 {@code @JsonProperty}를 붙이지 않는다
 * (walletPublicId → wallet_public_id 자동 변환).
 *
 * <p>명세 §0에 따라 금액(balance)과 시각(updatedAt)은 String으로 직렬화한다.
 */
@Getter
public class WalletBalanceResponse {

    @Schema(description = "전자지갑 식별자(UUID)", example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
    private final String walletPublicId;

    @Schema(description = "지갑 상태", example = "ACTIVE", allowableValues = {"ACTIVE", "SUSPENDED", "CLOSED"})
    private final String status;

    @Schema(description = "통화별 잔액 목록")
    private final List<BalanceItem> balances;

    @Schema(description = "잔액 최종 변경 시각(ISO 8601, UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String updatedAt;

    @Builder
    private WalletBalanceResponse(String walletPublicId, String status, List<BalanceItem> balances, String updatedAt) {
        this.walletPublicId = walletPublicId;
        this.status = status;
        this.balances = balances;
        this.updatedAt = updatedAt;
    }

    public static WalletBalanceResponse from(Wallet wallet, List<WalletBalance> balances) {
        List<BalanceItem> items = balances.stream()
                .map(BalanceItem::from)
                .toList();

        // updated_at = "잔액 최종 변경 시각" → balances 중 가장 최근 updatedAt.
        // balances가 비어 있으면 wallet.updatedAt으로 fallback하고, 둘 다 없으면 null.
        String latestUpdatedAt = balances.stream()
                .map(WalletBalance::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(WalletBalanceResponse::toUtcZ)
                .orElseGet(() -> toUtcZ(wallet.getUpdatedAt()));

        return WalletBalanceResponse.builder()
                .walletPublicId(wallet.getPublicId())
                .status(wallet.getStatus().name())
                .balances(items)
                .updatedAt(latestUpdatedAt)
                .build();
    }

    /**
     * LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다.
     * 초 단위로 절삭해 명세 mock 포맷("2026-05-26T04:15:30Z")과 일치시킨다.
     */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Getter
    public static class BalanceItem {

        @Schema(description = "통화 코드", example = "KRW", allowableValues = {"KRW", "USD", "PHP", "VND"})
        private final String currencyCode;

        @Schema(description = "잔액(소수점 4자리 고정 string)", example = "1530000.0000", type = "string")
        private final String balance;

        @Builder
        private BalanceItem(String currencyCode, String balance) {
            this.currencyCode = currencyCode;
            this.balance = balance;
        }

        public static BalanceItem from(WalletBalance walletBalance) {
            return BalanceItem.builder()
                    .currencyCode(walletBalance.getCurrencyCode().name())
                    // 금액은 소수점 4자리 고정 String (지수표기 방지). DB가 DECIMAL(18,4)이라 scale 확장은 안전.
                    .balance(walletBalance.getBalance().setScale(4).toPlainString())
                    .build();
        }
    }
}
