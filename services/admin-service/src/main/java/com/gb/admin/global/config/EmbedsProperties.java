package com.gb.admin.global.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 2(Grafana/ArgoCD 임베드) URL 구성.
 *
 * <p>application.yaml: {@code admin.monitoring.embeds.*} 와 1:1 바인딩.
 * base-url + path 형태로 최종 임베드 URL을 빌드한다(URL 조합은 {@code MonitoringServiceImpl.embeds()} 책임).
 *
 * <p>응답 JSON shape({@code grafana=[{name,label,url}], argocd={url}})은 프론트가 그대로 쓰고 있어 유지.
 * 본 properties는 path/base-url을 외부화하는 용도이며, 응답 키 이름은 바꾸지 않는다.
 */
@ConfigurationProperties(prefix = "admin.monitoring.embeds")
public record EmbedsProperties(
        Grafana grafana,
        Argocd argocd
) {

    /** Grafana 임베드 — 5개 대시보드의 path를 yml에서 외부화. */
    public record Grafana(
            String baseUrl,
            List<Dashboard> dashboards
    ) {
    }

    /** Grafana 대시보드 한 항목. name/label/path는 응답 그대로 매핑. */
    public record Dashboard(
            String name,
            String label,
            String path
    ) {
    }

    /** ArgoCD 임베드 — base-url + path 단일 항목. */
    public record Argocd(
            String baseUrl,
            String path
    ) {
    }
}
