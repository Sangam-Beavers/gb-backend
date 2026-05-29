package com.gb.wallet.global.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountNumberMaskerTest {

    @Test
    @DisplayName("정상 길이(8자 이상): 앞 3 + -****- + 뒤 4")
    void mask_정상_길이() {
        assertThat(AccountNumberMasker.mask("1234567891111")).isEqualTo("123-****-1111");
        assertThat(AccountNumberMasker.mask("12345678")).isEqualTo("123-****-5678"); // 경계: 8자
    }

    @Test
    @DisplayName("짧은 길이(<=7): 안전 fallback ****")
    void mask_짧은_길이() {
        assertThat(AccountNumberMasker.mask("1234567")).isEqualTo("****"); // 경계: 7자
        assertThat(AccountNumberMasker.mask("12")).isEqualTo("****");
        assertThat(AccountNumberMasker.mask("1")).isEqualTo("****");
    }

    @Test
    @DisplayName("null / 빈 문자열: 그대로 통과 (NPE 안 남)")
    void mask_null_또는_빈() {
        assertThat(AccountNumberMasker.mask(null)).isNull();
        assertThat(AccountNumberMasker.mask("")).isEqualTo("");
    }
}
