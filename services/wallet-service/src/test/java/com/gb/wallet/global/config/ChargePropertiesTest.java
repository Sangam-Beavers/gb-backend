package com.gb.wallet.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChargeProperties} 단위 테스트. 설정 미지정(=null 바인딩) 시 기존 동작(1천만원)을 보장하는지
 * (완료 조건 직접 검증)와, 명시한 한도 값이 그대로 보존되는지 확인한다.
 */
class ChargePropertiesTest {

    @Test
    @DisplayName("설정 미지정(null)이면 기본 단일거래 한도 1천만원으로 보정된다")
    void 설정_미지정시_기본값_1천만원() {
        assertThat(new ChargeProperties(null).singleLimit()).isEqualByComparingTo("10000000");
    }

    @Test
    @DisplayName("명시한 한도 값은 그대로 보존된다")
    void 명시값은_그대로_보존된다() {
        assertThat(new ChargeProperties(new BigDecimal("1000")).singleLimit())
                .isEqualByComparingTo("1000");
    }
}
