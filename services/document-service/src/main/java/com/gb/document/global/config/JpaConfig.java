package com.gb.document.global.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화. BaseEntity의 @CreatedDate/@LastModifiedDate가 채워지도록 한다.
 *
 * <p><b>시각 소스 = UTC 고정(10D member-core-4·community-3 계열):</b> 기본 Auditing은 JVM 기본존의
 * LocalDateTime을 캡처하는데, 응답 직렬화는 저장값을 무조건 UTC로 간주해 'Z'를 붙인다(conventions §5).
 * 비-UTC JVM(KST 등)에서 두 가정이 어긋나 오프셋이 틀어지므로, {@code utcDateTimeProvider}로 캡처
 * 시점부터 UTC를 명시해 'LocalDateTime = UTC' 가정을 코드로 보장한다(4서비스 공통 패턴).
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
public class JpaConfig {

    /** Auditing 시각 공급자 — JVM 기본존 대신 UTC를 명시 캡처한다(DATETIME 컬럼 저장값 = UTC). */
    @Bean
    public DateTimeProvider utcDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Hibernate 6 JSON 컬럼({@code @JdbcTypeCode(SqlTypes.JSON)})의 (역)직렬화 ObjectMapper를
     * <b>SNAKE_CASE</b>로 맞춘다. {@code document_results}의 {@code wage_summary}/{@code risk_items}는
     * 결과 JSON 스키마(SSOT, snake_case 예: {@code risk_level}, {@code currency_code})로 저장되는데,
     * Hibernate 기본 ObjectMapper에는 Spring 전역 SNAKE_CASE 전략이 적용되지 않아 record 필드
     * (camelCase: {@code riskLevel} 등)와 어긋나 역직렬화가 깨진다(읽기 시 500). 전역 방식으로 통일해
     * 레코드에 {@code @JsonProperty}를 붙이지 않는다(CLAUDE.md §5).
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES=false}로 결과 JSON에 record 미정의 필드가 있어도 무시한다.
     *
     * <p><b>금액 필드 관대 파싱({@link LenientBigDecimalDeserializer}):</b> 결과 JSON은 계정 B Lambda B가
     * 비결정적 LLM 출력으로 쓰는데, {@code deductions[].amount} 등 금액에 {@code "약 103,500원"}처럼
     * 통화기호·콤마·한글이 섞인 표시용 문자열이 들어오는 경우가 실측됐다(2026-06-08). {@code BigDecimal}
     * 역직렬화가 깨지면 그 행이 섞인 <b>목록 조회 전체가 500</b>으로 떨어지므로(엔티티 hydration 실패),
     * 읽기 측 안전망으로 숫자 외 문자를 제거해 파싱하고 실패 시 null로 강등한다. 이 매퍼는 Hibernate
     * JSON 컬럼 전용이라 API 요청/응답 ObjectMapper의 엄격함은 그대로 유지된다.
     */
    @Bean
    HibernatePropertiesCustomizer jsonSnakeCaseFormatMapper() {
        SimpleModule lenientNumbers = new SimpleModule()
                .addDeserializer(BigDecimal.class, new LenientBigDecimalDeserializer());
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .addModule(lenientNumbers)
                .build();
        return props -> props.put(
                AvailableSettings.JSON_FORMAT_MAPPER, new JacksonJsonFormatMapper(objectMapper));
    }
}
