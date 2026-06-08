package com.gb.admin.domain.monitoring.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Phase 2 Grafana/ArgoCD 임베드 URL placeholder")
public record EmbedsResponse(
        @Schema(description = "Grafana 대시보드 임베드 목록.")
        List<GrafanaEmbed> grafana,

        @Schema(description = "ArgoCD 임베드.")
        ArgoEmbed argocd
) {

    @Schema(description = "Grafana 임베드 단건")
    public record GrafanaEmbed(
            @Schema(description = "키.", example = "kubernetes_cluster")
            String name,

            @Schema(description = "라벨.", example = "Kubernetes Cluster")
            String label,

            @Schema(description = "임베드 URL.")
            String url
    ) {
    }

    @Schema(description = "ArgoCD 임베드")
    public record ArgoEmbed(
            @Schema(description = "임베드 URL.")
            String url
    ) {
    }
}
