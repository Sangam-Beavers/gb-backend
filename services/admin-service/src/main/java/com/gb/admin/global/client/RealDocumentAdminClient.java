package com.gb.admin.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.admin.global.config.InternalApiProperties;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@Profile("!mock-clients")
public class RealDocumentAdminClient implements DocumentAdminClient {

    private final RestClient restClient;
    private final String baseUrl;

    public RealDocumentAdminClient(RestClient internalApiRestClient, InternalApiProperties props) {
        this.restClient = internalApiRestClient;
        Map<String, String> urls = props.urls();
        this.baseUrl = urls == null ? null : urls.get("document");
        if (this.baseUrl == null || this.baseUrl.isBlank()) {
            throw new IllegalStateException("admin.internal-api.urls.document 가 비어 있습니다 — 환경변수 DOCUMENT_INTERNAL_URL 확인.");
        }
    }

    @Override
    public DocumentStats stats() {
        try {
            InternalApiEnvelope<DocStatsPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/documents/stats")
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<DocStatsPayload>>() {});
            if (env == null || env.data() == null) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            DocStatsPayload p = env.data();
            return new DocumentStats(p.todayAnalyzed(), p.successCount(), p.failedCount(), p.partialCount());
        } catch (BusinessException be) {
            throw be;
        } catch (RuntimeException e) {
            log.error("[RealDocumentAdminClient] stats 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Page<AdminDocumentSummary> recent(int page, int size) {
        try {
            InternalApiEnvelope<DocPagePayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/documents?page={p}&size={s}", page, size)
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<DocPagePayload>>() {});
            DocPagePayload p = env == null ? null : env.data();
            if (p == null || p.documents() == null) {
                return new PageImpl<>(List.of(), PageRequest.of(page, Math.max(size, 1)), 0);
            }
            List<AdminDocumentSummary> mapped = p.documents().stream()
                    .map(d -> new AdminDocumentSummary(
                            d.documentPublicId(),
                            d.userPublicId(),
                            null, // userName enrich by admin-service
                            d.analysisDocumentType(),
                            d.language(),
                            d.overallRiskLevel(),
                            d.followUpAction(),
                            d.analyzedAt()))
                    .collect(Collectors.toList());
            return new PageImpl<>(mapped, PageRequest.of(p.page(), Math.max(p.size(), 1)), p.totalElements());
        } catch (RuntimeException e) {
            log.error("[RealDocumentAdminClient] recent 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** stats 의 분석 성공률(외부 enrichment 필요 시). */
    public String aiAnalysisSuccessRate() {
        try {
            InternalApiEnvelope<DocStatsPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/documents/stats")
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<DocStatsPayload>>() {});
            return env == null || env.data() == null ? "0.0000" : env.data().aiAnalysisSuccessRate();
        } catch (RuntimeException e) {
            return "0.0000";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocStatsPayload(
            @JsonProperty("today_analyzed") long todayAnalyzed,
            @JsonProperty("success_count") long successCount,
            @JsonProperty("failed_count") long failedCount,
            @JsonProperty("partial_count") long partialCount,
            @JsonProperty("by_risk_level") Map<String, Long> byRiskLevel,
            @JsonProperty("ai_analysis_success_rate") String aiAnalysisSuccessRate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocPagePayload(
            @JsonProperty("documents") List<DocWire> documents,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocWire(
            @JsonProperty("document_public_id") String documentPublicId,
            @JsonProperty("user_public_id") String userPublicId,
            @JsonProperty("analysis_document_type") String analysisDocumentType,
            @JsonProperty("language") String language,
            @JsonProperty("overall_risk_level") String overallRiskLevel,
            @JsonProperty("follow_up_action") String followUpAction,
            @JsonProperty("analyzed_at") LocalDateTime analyzedAt) {
    }
}
