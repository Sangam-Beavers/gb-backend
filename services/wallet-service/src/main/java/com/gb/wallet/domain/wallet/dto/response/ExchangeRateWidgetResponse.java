package com.gb.wallet.domain.wallet.dto.response;

import com.gb.wallet.global.common.enums.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/v1/wallets/exchange-rates 응답 data. (메인 화면 환율 위젯)
 *
 * <p>기준 통화(KRW) 대비 주요 통화의 "1 외화→KRW" 환율 + 전일 대비 등락률(%)을 내려준다.
 * 환율은 명세 §0·§5 에 따라 {@code string} 십진수, 등락률(change_rate)은 표시 전용 {@code number}.
 *
 * <p>JSON 필드명은 전역 Jackson 설정(SNAKE_CASE)으로 자동 변환되므로 필드는 camelCase 로 둔다.
 */
@Getter
public class ExchangeRateWidgetResponse {

    @Schema(description = "기준 통화 코드. 항상 KRW (\"1 외화→KRW\" 환산 기준)", example = "KRW")
    private final String baseCurrencyCode;

    @Schema(description = "통화별 환율 목록")
    private final List<RateItem> rates;

    @Builder
    private ExchangeRateWidgetResponse(String baseCurrencyCode, List<RateItem> rates) {
        this.baseCurrencyCode = baseCurrencyCode;
        this.rates = rates;
    }

    /** 통화별 환율 항목 목록으로 응답을 조립한다. 기준 통화는 항상 KRW. */
    public static ExchangeRateWidgetResponse of(List<RateItem> rates) {
        return ExchangeRateWidgetResponse.builder()
                .baseCurrencyCode(CurrencyType.KRW.name())
                .rates(rates)
                .build();
    }

    /** Instant → ISO 8601 UTC 'Z' 문자열 (초 절삭, 명세 §0). 중첩 정적 클래스에서도 접근한다. */
    private static String toUtcZ(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    @Getter
    public static class RateItem {

        @Schema(description = "통화 코드 (ISO 4217)", example = "USD")
        private final String currencyCode;

        @Schema(description = "통화명(한국어)", example = "미국 달러")
        private final String currencyName;

        @Schema(description = "통화 기호", example = "$")
        private final String currencySymbol;

        @Schema(description = "환율 (\"1 외화→KRW\", string 십진수)", example = "1380.5000", type = "string")
        private final String exchangeRate;

        @Schema(description = "전일 대비 등락률(%). 표시 전용 number. 직전 값 없으면 0", example = "0.32")
        private final BigDecimal changeRate;

        @Schema(description = "환율 조회 기준 시각 (ISO 8601, UTC Z)", example = "2026-05-26T04:00:00Z")
        private final String updatedAt;

        @Builder
        private RateItem(String currencyCode, String currencyName, String currencySymbol,
                         String exchangeRate, BigDecimal changeRate, String updatedAt) {
            this.currencyCode = currencyCode;
            this.currencyName = currencyName;
            this.currencySymbol = currencySymbol;
            this.exchangeRate = exchangeRate;
            this.changeRate = changeRate;
            this.updatedAt = updatedAt;
        }

        /**
         * 통화 1건의 환율 항목을 만든다.
         *
         * @param currency      통화
         * @param rateToKrw     "1 외화→KRW" 현재 환율 (null 아님 — 호출 측 Service 에서 사전 검증)
         * @param prevRateToKrw 직전(전일) "1 외화→KRW" 환율. null 이면 등락률 0
         * @param asOf          조회 기준 시각 (모든 항목 동일하게 전달)
         */
        public static RateItem of(CurrencyType currency, BigDecimal rateToKrw,
                                  BigDecimal prevRateToKrw, Instant asOf) {
            return RateItem.builder()
                    .currencyCode(currency.name())
                    .currencyName(currency.getKoreanName())
                    .currencySymbol(currency.getSymbol())
                    .exchangeRate(rateToKrw.setScale(4, RoundingMode.HALF_UP).toPlainString())
                    .changeRate(calcChangeRate(rateToKrw, prevRateToKrw))
                    .updatedAt(toUtcZ(asOf))
                    .build();
        }

        /** (현재 − 직전) / 직전 × 100, 소수점 2자리. 직전 값이 없거나 0이면 0.00 (frontend 표시 단순화). */
        private static BigDecimal calcChangeRate(BigDecimal current, BigDecimal prev) {
            if (prev == null || prev.signum() == 0) {
                return BigDecimal.ZERO.setScale(2);
            }
            return current.subtract(prev)
                    .divide(prev, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }
}
