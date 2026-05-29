package com.gb.wallet.domain.transaction.dto.response;

import com.gb.wallet.global.common.enums.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/v1/transfers/supported-currencies 응답 data.
 *
 * <p>지원 통화 4종(KRW/USD/PHP/VND)을 {@link CurrencyType}에서 그대로 가져와 직렬화한다.
 * JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환된다
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE}).
 */
@Getter
public class SupportedCurrenciesResponse {

    @Schema(description = "지원 통화 목록 (CurrencyType enum SSOT 기반)")
    private final List<CurrencyItem> currencies;

    private SupportedCurrenciesResponse(List<CurrencyItem> currencies) {
        this.currencies = currencies;
    }

    public static SupportedCurrenciesResponse of(List<CurrencyItem> currencies) {
        return new SupportedCurrenciesResponse(currencies);
    }

    @Getter
    public static class CurrencyItem {

        @Schema(description = "통화 코드(ISO 4217)", example = "KRW",
                allowableValues = {"KRW", "USD", "PHP", "VND"})
        private final String code;

        @Schema(description = "통화 영문 명칭", example = "Korean Won")
        private final String name;

        @Schema(description = "통화 기호", example = "₩")
        private final String symbol;

        @Builder
        private CurrencyItem(String code, String name, String symbol) {
            this.code = code;
            this.name = name;
            this.symbol = symbol;
        }

        public static CurrencyItem from(CurrencyType currency) {
            return CurrencyItem.builder()
                    .code(currency.name())
                    .name(currency.getDisplayName())
                    .symbol(currency.getSymbol())
                    .build();
        }
    }
}
