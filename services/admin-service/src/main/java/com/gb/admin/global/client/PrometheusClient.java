package com.gb.admin.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Prometheus instant query 클라이언트 — 인프라 경보(RDS/ElastiCache 등) 계산용.
 *
 * <p>{@code GET {base}/api/v1/query?query=<promql>} 호출. PromQL에 임계 조건(예: {@code > 80})을 넣어
 * 결과 시리즈가 있으면 = 발화 중인 경보로 본다. {@code admin.prometheus.base-url}이 비어 있거나 호출이
 * 실패하면 빈 결과로 fail-soft 한다(경보 화면이 깨지지 않게).
 */
@Slf4j
@Component
public class PrometheusClient {

    private final RestClient restClient;
    private final String baseUrl;

    public PrometheusClient(RestClient monitoringRestClient,
                            @Value("${admin.prometheus.base-url:}") String baseUrl) {
        this.restClient = monitoringRestClient;
        this.baseUrl = baseUrl;
    }

    public boolean enabled() {
        return StringUtils.hasText(baseUrl);
    }

    /** PromQL instant query 실행. 각 결과 시리즈를 (labels, value) 샘플로 반환. 실패 시 빈 리스트. */
    public List<PromSample> query(String promql) {
        if (!enabled()) {
            return List.of();
        }
        try {
            PromResponse res = restClient.get()
                    .uri(baseUrl + "/api/v1/query?query={q}", promql)
                    .retrieve()
                    .body(PromResponse.class);
            if (res == null || res.data() == null || res.data().result() == null) {
                return List.of();
            }
            return res.data().result().stream()
                    .map(r -> new PromSample(
                            r.metric() == null ? Map.of() : r.metric(),
                            (r.value() == null || r.value().size() < 2) ? null : String.valueOf(r.value().get(1))))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("[PrometheusClient] query 실패(fail-soft): q={}, msg={}", promql, e.getMessage());
            return List.of();
        }
    }

    /** 단일 결과 시리즈 — 라벨 맵 + 값(문자열). */
    public record PromSample(Map<String, String> labels, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromResponse(@JsonProperty("status") String status, @JsonProperty("data") PromData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromData(@JsonProperty("resultType") String resultType, @JsonProperty("result") List<PromResult> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromResult(@JsonProperty("metric") Map<String, String> metric, @JsonProperty("value") List<Object> value) {
    }
}
