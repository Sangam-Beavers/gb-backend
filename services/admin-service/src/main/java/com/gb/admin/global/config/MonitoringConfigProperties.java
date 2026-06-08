package com.gb.admin.global.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영 정책 mirror — Phase 1은 admin-service yml에 박아둔다(다음 스프린트에서 wallet과 동기화).
 *
 * <p>{@code monitoring/config} API가 이 값을 그대로 노출한다. value는 모두 String으로 유지해
 * BigDecimal/long을 한 응답 모양에 혼합 노출 가능(currency 표기와 같이 표시 컨벤션을 통제).
 */
@ConfigurationProperties(prefix = "admin.monitoring")
public record MonitoringConfigProperties(
        Map<String, String> configs
) {
}
