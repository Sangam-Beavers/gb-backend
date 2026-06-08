package com.gb.document.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.gb.document.domain.document.entity.WageSummary;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LenientBigDecimalDeserializer} 단위 테스트.
 *
 * <p>회귀 방지 대상: Lambda B(LLM)가 {@code deductions[].amount}에 {@code "약 103,500원"} 같은
 * 표시용 문자열을 넣어 목록 조회 전체가 500으로 떨어진 사건(2026-06-08). JpaConfig의 JSON 포맷
 * 매퍼와 동일하게 ObjectMapper를 구성해 같은 경로를 검증한다.
 */
class LenientBigDecimalDeserializerTest {

    // JpaConfig.jsonSnakeCaseFormatMapper()와 동일 구성 — Hibernate JSON 컬럼 역직렬화 경로 재현.
    private final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addModule(new SimpleModule().addDeserializer(
                    BigDecimal.class, new LenientBigDecimalDeserializer()))
            .build();

    @Test
    @DisplayName("통화기호·콤마·한글이 섞인 금액 문자열도 숫자만 추려 파싱한다(목록 500 회귀 방지)")
    void 더러운_금액_문자열을_파싱한다() throws Exception {
        String json = """
                {"currency_code":"KRW","monthly_wage":"2,000,000원","hourly_wage":"9620",
                 "deductions":[{"name":"national_pension","amount":"약 103,500원"}]}
                """;

        WageSummary wage = mapper.readValue(json, WageSummary.class);

        assertThat(wage.monthlyWage()).isEqualByComparingTo("2000000");
        assertThat(wage.hourlyWage()).isEqualByComparingTo("9620");
        assertThat(wage.deductions()).hasSize(1);
        assertThat(wage.deductions().get(0).amount()).isEqualByComparingTo("103500");
    }

    @Test
    @DisplayName("순수 숫자/JSON 숫자 토큰은 그대로 파싱한다")
    void 정상_금액은_그대로_파싱한다() throws Exception {
        // amount가 따옴표 없는 숫자 토큰으로 와도 처리되는지 함께 확인.
        String json = """
                {"currency_code":"KRW","monthly_wage":"2000000","hourly_wage":null,
                 "deductions":[{"name":"income_tax","amount":90000}]}
                """;

        WageSummary wage = mapper.readValue(json, WageSummary.class);

        assertThat(wage.monthlyWage()).isEqualByComparingTo("2000000");
        assertThat(wage.hourlyWage()).isNull();
        assertThat(wage.deductions().get(0).amount()).isEqualByComparingTo("90000");
    }

    @Test
    @DisplayName("파싱 불가/빈 금액은 예외 대신 null로 강등한다")
    void 파싱불가_금액은_null로_강등한다() throws Exception {
        String json = """
                {"currency_code":"KRW","monthly_wage":"N/A","hourly_wage":"",
                 "deductions":[{"name":"unknown","amount":"-"}]}
                """;

        WageSummary wage = mapper.readValue(json, WageSummary.class);

        assertThat(wage.monthlyWage()).isNull();
        assertThat(wage.hourlyWage()).isNull();
        assertThat(wage.deductions().get(0).amount()).isNull();
    }

    @Test
    @DisplayName("한 행의 더러운 금액이 역직렬화 예외를 던지지 않는다")
    void 역직렬화는_예외없이_완료된다() {
        String json = """
                {"currency_code":"KRW","monthly_wage":"월 250만원 상당",
                 "deductions":[{"name":"건강보험","amount":"대략 7만8천원"}]}
                """;

        assertThatCode(() -> mapper.readValue(json, WageSummary.class))
                .doesNotThrowAnyException();
    }
}
