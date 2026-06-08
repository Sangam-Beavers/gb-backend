package com.gb.admin.global.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 다른 서비스(/actuator/health) 호출 base URL.
 *
 * <p>application.yaml의 {@code admin.service-health.*}를 바인딩한다. timeout-ms는 service-health
 * 응답 대기 상한(밀리초). 호출 실패/타임아웃 시 status="DOWN", response_time_ms=null로 보고된다.
 */
@ConfigurationProperties(prefix = "admin.service-health")
public record ServiceHealthProperties(
        Map<String, String> urls,
        int timeoutMs
) {
}
