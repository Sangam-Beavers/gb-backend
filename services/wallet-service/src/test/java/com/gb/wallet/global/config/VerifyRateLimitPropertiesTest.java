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
 * {@link VerifyRateLimitProperties} 단위 테스트.
 *
 * <p>(1) canonical constructor의 기본값 보정(null→60/10)이 유지되는지, (2) {@code @Min(1)} 제약이 record
 * component에 실제로 적용돼 {@code 0}/음수를 제약 위반으로 잡는지(= 기동 시 바인딩 실패로 이어질 값)를
 * Validator로 직접 검증한다. 바인딩 순서상 미지정(null)은 기본값으로 치환된 뒤 검증되므로 통과한다.
 */
class VerifyRateLimitPropertiesTest {

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
        VerifyRateLimitProperties props = new VerifyRateLimitProperties(null, null);

        assertThat(props.windowSeconds()).isEqualTo(60);
        assertThat(props.limit()).isEqualTo(10);
        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    @DisplayName("명시한 양수 값은 그대로 보존되고 제약을 통과한다")
    void 명시값_보존_및_통과() {
        VerifyRateLimitProperties props = new VerifyRateLimitProperties(30, 5);

        assertThat(props.windowSeconds()).isEqualTo(30);
        assertThat(props.limit()).isEqualTo(5);
        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    @DisplayName("경계값 1은 통과한다(@Min(1))")
    void 경계값_1_통과() {
        assertThat(validator.validate(new VerifyRateLimitProperties(1, 1))).isEmpty();
    }

    @Test
    @DisplayName("limit이 0/음수면 @Min(1) 위반 — 기동 시 바인딩 실패로 fail-fast")
    void limit_영이하_제약위반() {
        assertThat(validator.validate(new VerifyRateLimitProperties(60, 0))).isNotEmpty();
        assertThat(validator.validate(new VerifyRateLimitProperties(60, -1))).isNotEmpty();
    }

    @Test
    @DisplayName("windowSeconds가 0/음수면 @Min(1) 위반 — 기동 시 바인딩 실패로 fail-fast")
    void windowSeconds_영이하_제약위반() {
        assertThat(validator.validate(new VerifyRateLimitProperties(0, 10))).isNotEmpty();
        assertThat(validator.validate(new VerifyRateLimitProperties(-5, 10))).isNotEmpty();
    }
}
