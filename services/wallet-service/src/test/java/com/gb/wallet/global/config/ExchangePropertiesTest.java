package com.gb.wallet.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ExchangeProperties} 단위 테스트(ChargePropertiesTest와 동일 패턴). 설정 미지정(=null 바인딩) 시
 * 기존 동작(수수료율 0.5%)을 보장하는지와, 명시한 값이 그대로 보존되는지 확인한다(10D wallet-exchange-5).
 */
class ExchangePropertiesTest {

    @Test
    @DisplayName("설정 미지정(null)이면 기본 수수료율 0.5%로 보정된다(기존 하드코딩과 동작 불변)")
    void 설정_미지정시_기본값_0_5퍼센트() {
        assertThat(new ExchangeProperties(null).feeRate()).isEqualByComparingTo("0.005");
    }

    @Test
    @DisplayName("명시한 수수료율 값은 그대로 보존된다")
    void 명시값은_그대로_보존된다() {
        assertThat(new ExchangeProperties(new BigDecimal("0.01")).feeRate())
                .isEqualByComparingTo("0.01");
    }
}
