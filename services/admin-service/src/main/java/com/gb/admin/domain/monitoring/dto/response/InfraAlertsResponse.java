package com.gb.admin.domain.monitoring.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 인프라 경보 응답 — Prometheus(YACE) 지표 기준 RDS/ElastiCache 등 위험 조건.
 *
 * <p>프론트 통합 경보 배너/헤더 뱃지가 앱 레벨 경보(스냅샷 계산)와 합쳐 표시한다.
 * level은 프론트와 맞춰 소문자 {@code "critical"|"warning"}.
 */
@Schema(description = "인프라 경보 응답")
public record InfraAlertsResponse(
        @Schema(description = "발화 중인 경보 목록")
        List<Alert> alerts
) {
    @Schema(description = "경보 단건")
    public record Alert(
            @Schema(description = "심각도", example = "warning", allowableValues = {"critical", "warning"})
            String level,
            @Schema(description = "출처", example = "RDS")
            String source,
            @Schema(description = "표시 문구", example = "RDS CPU 높음 — sb-stage-core-1 86.2%")
            String text
    ) {
    }
}
