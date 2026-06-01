package com.gb.wallet.domain.exchange.dto.response;

import com.gb.wallet.global.common.enums.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 환전·재환전 지원 통화 목록 응답.
 *
 * <p>통화 마스터 테이블이 없으므로 {@link CurrencyType} enum이 SSOT다. enum의 4종(KRW/USD/PHP/VND)을
 * 코드·이름·기호로 변환해 내려준다. (송금 도메인의 동일 응답과 구조는 같으나, 도메인 분리 원칙(CLAUDE §3)에
 * 따라 exchange 도메인은 자기 DTO를 둔다.)
 */
@Getter
public class SupportedCurrenciesResponse {

    @Schema(description = "지원 통화 목록")
    private final List<CurrencyInfo> currencies;

    @Builder
    private SupportedCurrenciesResponse(List<CurrencyInfo> currencies) {
        this.currencies = currencies;
    }

    /** 지원 통화 enum 전체를 통화 정보 목록으로 변환한다. */
    public static SupportedCurrenciesResponse of() {
        List<CurrencyInfo> list = Arrays.stream(CurrencyType.values())
                .map(CurrencyInfo::from)
                .toList();
        return SupportedCurrenciesResponse.builder()
                .currencies(list)
                .build();
    }

    @Getter
    public static class CurrencyInfo {

        @Schema(description = "통화 코드 (ISO 4217)", example = "USD")
        private final String currencyCode;

        @Schema(description = "통화명", example = "US Dollar")
        private final String currencyName;

        @Schema(description = "통화 기호", example = "$")
        private final String currencySymbol;

        @Builder
        private CurrencyInfo(String currencyCode, String currencyName, String currencySymbol) {
            this.currencyCode = currencyCode;
            this.currencyName = currencyName;
            this.currencySymbol = currencySymbol;
        }

        public static CurrencyInfo from(CurrencyType currency) {
            return CurrencyInfo.builder()
                    .currencyCode(currency.name())
                    .currencyName(currency.getDisplayName())
                    .currencySymbol(currency.getSymbol())
                    .build();
        }
    }
}
