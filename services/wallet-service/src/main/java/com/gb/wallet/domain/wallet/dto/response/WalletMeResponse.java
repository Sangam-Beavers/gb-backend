package com.gb.wallet.domain.wallet.dto.response;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.global.common.enums.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/v1/wallets/me 응답 data.
 *
 * <p>보유 통화별 잔액 + "1 외화→KRW" 환율 + 통화별 원화 환산액 + 전체 합산 원화 평가액.
 *
 * <p>JSON 필드명은 전역 Jackson 설정(application.yaml) 의 SNAKE_CASE 변환으로 자동 처리되므로
 * 필드는 camelCase 로 두고 {@code @JsonProperty} 를 붙이지 않는다. 금액·환율·합계는 명세 §0·§5 에
 * 따라 모두 {@code string} 십진수 (지수표기 방지) 로 직렬화한다.
 */
@Getter
public class WalletMeResponse {

    @Schema(description = "전자지갑 식별자(UUID)", example = "a1b2c3d4-1111-2222-3333-444455556666")
    private final String walletPublicId;

    @Schema(description = "지갑 상태", example = "ACTIVE", allowableValues = {"ACTIVE", "SUSPENDED", "CLOSED"})
    private final String status;

    @Schema(description = "전체 보유 통화의 원화 환산 합계 (string 십진수)", example = "1888500.0000", type = "string")
    private final String totalBalanceInKrw;

    @Schema(description = "통화별 잔액 및 원화 환산 목록")
    private final List<BalanceWithKrwItem> balances;

    @Schema(description = "잔액·환산 기준 시각(ISO 8601, UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String updatedAt;

    @Builder
    private WalletMeResponse(String walletPublicId, String status, String totalBalanceInKrw,
                             List<BalanceWithKrwItem> balances, String updatedAt) {
        this.walletPublicId = walletPublicId;
        this.status = status;
        this.totalBalanceInKrw = totalBalanceInKrw;
        this.balances = balances;
        this.updatedAt = updatedAt;
    }

    /**
     * 잔액 + 환율 맵으로부터 응답을 조립한다.
     *
     * @param wallet     지갑 엔티티
     * @param balances   통화별 잔액 목록 (KRW 포함)
     * @param ratesToKrw 통화별 "1 외화→KRW" 환율 (KRW 는 1). null 값이 포함돼 있으면 호출 측
     *                   (Service) 에서 사전에 거부했어야 하며, 본 메서드는 안전망으로 rate=1 처리한다.
     */
    public static WalletMeResponse from(Wallet wallet, List<WalletBalance> balances,
                                        Map<CurrencyType, BigDecimal> ratesToKrw) {
        // 통화별 환산
        List<BalanceWithKrwItem> items = balances.stream()
                .map(b -> BalanceWithKrwItem.from(b, ratesToKrw.get(b.getCurrencyCode())))
                .toList();

        // 전체 합산 (모든 통화의 balance_in_krw 합계)
        BigDecimal totalKrw = items.stream()
                .map(item -> new BigDecimal(item.getBalanceInKrw()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // updated_at = balances 중 가장 최근 updatedAt → 비었으면 wallet.updatedAt
        String latestUpdatedAt = balances.stream()
                .map(WalletBalance::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(WalletMeResponse::toUtcZ)
                .orElseGet(() -> toUtcZ(wallet.getUpdatedAt()));

        return WalletMeResponse.builder()
                .walletPublicId(wallet.getPublicId())
                .status(wallet.getStatus().name())
                .totalBalanceInKrw(totalKrw.setScale(4, RoundingMode.HALF_UP).toPlainString())
                .balances(items)
                .updatedAt(latestUpdatedAt)
                .build();
    }

    /** LocalDateTime → ISO 8601 UTC 'Z' 문자열 (초 절삭, 명세 §0). */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Getter
    public static class BalanceWithKrwItem {

        @Schema(description = "통화 코드", example = "KRW", allowableValues = {"KRW", "USD", "PHP", "VND"})
        private final String currencyCode;

        @Schema(description = "해당 통화 잔액 (소수점 4자리 string)", example = "1530000.0000", type = "string")
        private final String balance;

        @Schema(description = "적용 환율 (\"1 외화→KRW\" 고정, string 십진수). KRW 는 \"1\"",
                example = "1380.0000", type = "string")
        private final String exchangeRate;

        @Schema(description = "해당 통화 잔액의 원화 환산액", example = "345000.0000", type = "string")
        private final String balanceInKrw;

        @Builder
        private BalanceWithKrwItem(String currencyCode, String balance, String exchangeRate, String balanceInKrw) {
            this.currencyCode = currencyCode;
            this.balance = balance;
            this.exchangeRate = exchangeRate;
            this.balanceInKrw = balanceInKrw;
        }

        /**
         * 잔액 1건을 환산 항목으로 변환.
         *
         * @param walletBalance 잔액 엔티티
         * @param rateToKrw     "1 외화→KRW" 환율. null 이면 안전망으로 1 처리 (Service 단에서 사전 거부 가정)
         */
        public static BalanceWithKrwItem from(WalletBalance walletBalance, BigDecimal rateToKrw) {
            BigDecimal balance = walletBalance.getBalance();
            BigDecimal rate = (rateToKrw != null) ? rateToKrw : BigDecimal.ONE;
            BigDecimal inKrw = balance.multiply(rate);

            return BalanceWithKrwItem.builder()
                    .currencyCode(walletBalance.getCurrencyCode().name())
                    .balance(balance.setScale(4, RoundingMode.HALF_UP).toPlainString())
                    .exchangeRate(rate.setScale(4, RoundingMode.HALF_UP).toPlainString())
                    .balanceInKrw(inKrw.setScale(4, RoundingMode.HALF_UP).toPlainString())
                    .build();
        }
    }
}
