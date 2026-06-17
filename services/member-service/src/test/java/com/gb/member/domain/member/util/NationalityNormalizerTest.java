package com.gb.member.domain.member.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * NationalityNormalizer 단위 테스트 — 별칭→코드 수렴 + 대문자/trim 처리.
 */
class NationalityNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @DisplayName("한글/영문 별칭은 ISO 코드로 수렴한다")
    @CsvSource({
            "한국, KR",
            "대한민국, KR",
            "Korea, KR",
            "KOR, KR",
            "미국, US",
            "USA, US",
            "베트남, VN",
            "Vietnam, VN",
            "필리핀, PH",
            "기타, ETC",
            "other, ETC",
            "네팔, NP",
            "우즈베키스탄, UZ",
    })
    void alias_maps_to_code(String input, String expected) {
        assertThat(NationalityNormalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @DisplayName("코드는 대문자로 통일(소문자/혼합 입력 수용)")
    @CsvSource({
            "KR, KR",
            "kr, KR",
            "vn, VN",
            "Ph, PH",
            "NP, NP",
            "us, US",
    })
    void code_is_uppercased(String input, String expected) {
        assertThat(NationalityNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("앞뒤 공백은 제거 후 매핑된다")
    void trims_whitespace() {
        assertThat(NationalityNormalizer.normalize("  한국  ")).isEqualTo("KR");
        assertThat(NationalityNormalizer.normalize(" kr ")).isEqualTo("KR");
    }

    @Test
    @DisplayName("null/빈값은 그대로 반환(형식 검증은 Bean Validation 담당)")
    void blank_passthrough() {
        assertThat(NationalityNormalizer.normalize(null)).isNull();
        assertThat(NationalityNormalizer.normalize("")).isEqualTo("");
        assertThat(NationalityNormalizer.normalize("   ")).isEqualTo("");
    }

    @Test
    @DisplayName("매핑에 없는 값은 대문자로 통과(거부하지 않음)")
    void unknown_passthrough_uppercased() {
        assertThat(NationalityNormalizer.normalize("Japan")).isEqualTo("JAPAN");
    }
}
