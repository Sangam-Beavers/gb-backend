package com.gb.wallet.global.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountNumberMaskerTest {

    @Test
    @DisplayName("정상 길이(6자 이상): 앞 3 + 별표 + 뒤 2 (보수적 노출, WACC-08)")
    void mask_정상_길이() {
        assertThat(AccountNumberMasker.mask("1234567891111")).isEqualTo("123********11"); // 13자
        assertThat(AccountNumberMasker.mask("12345678")).isEqualTo("123***78");          // 경계: 8자
        assertThat(AccountNumberMasker.mask("123456")).isEqualTo("123*56");              // 경계: 6자
    }

    @Test
    @DisplayName("짧은 길이(<=5): 앞3/뒤2가 겹치므로 전체 마스킹(길이만큼 별표)")
    void mask_짧은_길이() {
        assertThat(AccountNumberMasker.mask("12345")).isEqualTo("*****"); // 경계: 5자
        assertThat(AccountNumberMasker.mask("12")).isEqualTo("**");
        assertThat(AccountNumberMasker.mask("1")).isEqualTo("*");
    }

    @Test
    @DisplayName("null / 빈 문자열: 그대로 통과 (NPE 안 남)")
    void mask_null_또는_빈() {
        assertThat(AccountNumberMasker.mask(null)).isNull();
        assertThat(AccountNumberMasker.mask("")).isEqualTo("");
    }
}
