package com.gb.community.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화. BaseEntity의 @CreatedDate/@LastModifiedDate가 채워지도록 한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
