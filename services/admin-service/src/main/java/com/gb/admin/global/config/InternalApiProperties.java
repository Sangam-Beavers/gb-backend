package com.gb.admin.global.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 도메인 서비스 /internal/admin/* 호출 base URL.
 *
 * <p>application.yaml의 {@code admin.internal-api.*}를 바인딩한다.
 * 다음 스프린트에 mTLS·NetworkPolicy로 격리(현재는 plain HTTP — 발표용).
 */
@ConfigurationProperties(prefix = "admin.internal-api")
public record InternalApiProperties(
        Map<String, String> urls,
        int connectTimeoutMs,
        int readTimeoutMs
) {
}
