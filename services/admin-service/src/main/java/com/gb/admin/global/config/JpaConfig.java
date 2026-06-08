package com.gb.admin.global.config;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화. BaseEntity의 @CreatedDate/@LastModifiedDate가 채워지도록 한다.
 *
 * <p><b>시각 소스 = UTC 고정:</b> 기본 Auditing은 JVM 기본존의 LocalDateTime을 캡처하는데, 응답 직렬화는
 * 저장값을 무조건 UTC로 간주해 'Z'를 붙인다(conventions §5). 비-UTC JVM(KST 등)에서 두 가정이 어긋나
 * 오프셋이 틀어지므로, {@code utcDateTimeProvider}로 캡처 시점부터 UTC를 명시해 'LocalDateTime = UTC'
 * 가정을 코드로 보장한다(4서비스 공통 패턴).
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
public class JpaConfig {

    /** Auditing 시각 공급자 — JVM 기본존 대신 UTC를 명시 캡처한다(DATETIME 컬럼 저장값 = UTC). */
    @Bean
    public DateTimeProvider utcDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now(ZoneOffset.UTC));
    }
}
