package com.gb.wallet.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PinVerifyRateLimitProperties} 단위 테스트(VerifyRateLimitPropertiesTest와 동일 패턴).
 *
 * <p>PIN 검증 rate-limit 정책은 계좌-verify와 독립된 전용 클래스(wallet-pin-redis-1)라 자체 바인딩 계약을
 * 검증한다: (1) canonical constructor의 기본값 보정(null→60/10), (2) {@code @Min(1)}이 component에 적용돼
 * {@code 0}/음수를 잡는지(기동 시 fail-fast). 미지정(null)은 기본값 치환 후 검증되므로 통과한다.
 */
class PinVerifyRateLimitPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("설정 미지정(null)이면 기본값(60s/10회)으로 보정되고 제약을 통과한다")
    void 미지정시_기본값_보정_및_통과() {
        PinVerifyRateLimitProperties props = new PinVerifyRateLimitProperties(null, null);

        assertThat(props.windowSeconds()).isEqualTo(60);
        assertThat(props.limit()).isEqualTo(10);
        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    @DisplayName("명시한 양수 값은 그대로 보존되고 제약을 통과한다")
    void 명시값_보존_및_통과() {
        PinVerifyRateLimitProperties props = new PinVerifyRateLimitProperties(30, 5);

        assertThat(props.windowSeconds()).isEqualTo(30);
        assertThat(props.limit()).isEqualTo(5);
        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    @DisplayName("경계값 1은 통과한다(@Min(1))")
    void 경계값_1_통과() {
        assertThat(validator.validate(new PinVerifyRateLimitProperties(1, 1))).isEmpty();
    }

    @Test
    @DisplayName("limit이 0/음수면 @Min(1) 위반 — 기동 시 바인딩 실패로 fail-fast")
    void limit_영이하_제약위반() {
        assertThat(validator.validate(new PinVerifyRateLimitProperties(60, 0))).isNotEmpty();
        assertThat(validator.validate(new PinVerifyRateLimitProperties(60, -1))).isNotEmpty();
    }

    @Test
    @DisplayName("windowSeconds가 0/음수면 @Min(1) 위반 — 기동 시 바인딩 실패로 fail-fast")
    void windowSeconds_영이하_제약위반() {
        assertThat(validator.validate(new PinVerifyRateLimitProperties(0, 10))).isNotEmpty();
        assertThat(validator.validate(new PinVerifyRateLimitProperties(-5, 10))).isNotEmpty();
    }
}
