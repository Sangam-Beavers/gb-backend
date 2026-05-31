package com.gb.wallet.domain.exchange.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse.CurrencyInfo;
import com.gb.wallet.global.common.enums.CurrencyType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지원 통화 목록 조회 단위 테스트.
 *
 * <p>DB·외부 의존이 없는 순수 변환 로직이라 스프링 컨텍스트 없이 직접 호출해 검증한다.
 * CurrencyType enum 전체가 코드·이름·기호와 함께 빠짐없이 반환되는지 본다.
 */
class ExchangeServiceImplTest {

    private final ExchangeServiceImpl exchangeService = new ExchangeServiceImpl();

    @Test
    @DisplayName("지원 통화 목록은 CurrencyType 전체를 코드·이름·기호와 함께 반환한다")
    void getSupportedCurrencies_전체통화_반환() {
        // when
        SupportedCurrenciesResponse response = exchangeService.getSupportedCurrencies();

        // then — 개수가 enum 전체와 일치
        List<CurrencyInfo> currencies = response.getCurrencies();
        assertThat(currencies).hasSize(CurrencyType.values().length);

        // 코드 목록이 enum 이름 전체와 일치(순서 무관)
        assertThat(currencies).extracting(CurrencyInfo::getCurrencyCode)
                .containsExactlyInAnyOrder("KRW", "USD", "PHP", "VND");
    }

    @Test
    @DisplayName("각 통화는 enum의 이름·기호를 그대로 담는다")
    void getSupportedCurrencies_이름과기호_매핑() {
        // when
        List<CurrencyInfo> currencies = exchangeService.getSupportedCurrencies().getCurrencies();

        // then — USD가 enum 정의(US Dollar, $)와 일치하는지 한 건 대표 검증
        CurrencyInfo usd = currencies.stream()
                .filter(c -> c.getCurrencyCode().equals("USD"))
                .findFirst()
                .orElseThrow();
        assertThat(usd.getCurrencyName()).isEqualTo(CurrencyType.USD.getDisplayName());
        assertThat(usd.getCurrencySymbol()).isEqualTo(CurrencyType.USD.getSymbol());
    }
}
