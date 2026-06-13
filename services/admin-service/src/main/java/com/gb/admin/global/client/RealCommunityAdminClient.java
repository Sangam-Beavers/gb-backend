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
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@Profile("!mock-clients")
public class RealCommunityAdminClient implements CommunityAdminClient {

    private final RestClient restClient;
    private final String baseUrl;

    public RealCommunityAdminClient(RestClient internalApiRestClient, InternalApiProperties props) {
        this.restClient = internalApiRestClient;
        Map<String, String> urls = props.urls();
        this.baseUrl = urls == null ? null : urls.get("community");
        if (this.baseUrl == null || this.baseUrl.isBlank()) {
            throw new IllegalStateException("admin.internal-api.urls.community 가 비어 있습니다 — 환경변수 COMMUNITY_INTERNAL_URL 확인.");
        }
    }

    @Override
    public Page<AdminReportSummary> reports(String status, String reason, int page, int size) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/internal/admin/reports")
                    .queryParam("page", page).queryParam("size", size);
            if (status != null && !status.isBlank()) b.queryParam("status", status);
            if (reason != null && !reason.isBlank()) b.queryParam("reason", reason);
            InternalApiEnvelope<ReportPagePayload> env = restClient.get().uri(b.build().toUri())
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<ReportPagePayload>>() {});
            ReportPagePayload p = env == null ? null : env.data();
            if (p == null || p.reports() == null) {
                return new PageImpl<>(List.of(), PageRequest.of(page, Math.max(size, 1)), 0);
            }
            List<AdminReportSummary> mapped = p.reports().stream()
                    .map(r -> new AdminReportSummary(
                            r.postPublicId(),
                            r.postTitle(),
                            r.authorPublicId(),
                            null, // nickname enrich
                            r.reportCount(),
                            r.reason(),
                            r.targetType(),
                            r.status(),
                            r.lastReportedAt()))
                    .collect(Collectors.toList());
            return new PageImpl<>(mapped, PageRequest.of(p.page(), Math.max(p.size(), 1)), p.totalElements());
        } catch (RuntimeException e) {
            log.error("[RealCommunityAdminClient] reports 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void hidePost(String postPublicId, String adminPublicId) {
        try {
            restClient.post()
                    .uri(baseUrl + "/api/v1/internal/admin/posts/{id}/hide", postPublicId)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("[RealCommunityAdminClient] hidePost 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void deletePost(String postPublicId, String adminPublicId) {
        try {
            restClient.delete()
                    .uri(baseUrl + "/api/v1/internal/admin/posts/{id}", postPublicId)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("[RealCommunityAdminClient] deletePost 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void dismissReports(String postPublicId, String adminPublicId) {
        try {
            restClient.post()
                    .uri(baseUrl + "/api/v1/internal/admin/posts/{id}/dismiss", postPublicId)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("[RealCommunityAdminClient] dismissReports 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public AdminPostDetail postDetail(String postPublicId) {
        try {
            InternalApiEnvelope<PostDetailPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/posts/{id}", postPublicId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<PostDetailPayload>>() {});
            if (env == null || env.data() == null) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            PostDetailPayload p = env.data();
            return new AdminPostDetail(p.postPublicId(), p.userPublicId(), p.title(),
                    p.content(), p.language(), p.createdAt());
        } catch (BusinessException be) {
            throw be;
        } catch (RuntimeException e) {
            log.error("[RealCommunityAdminClient] postDetail 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public long pendingReportCount() {
        try {
            InternalApiEnvelope<ReportStatsPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/stats/reports")
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<ReportStatsPayload>>() {});
            return env == null || env.data() == null ? 0L : env.data().pendingReportCount();
        } catch (RuntimeException e) {
            log.warn("[RealCommunityAdminClient] pendingReportCount 실패(fail-open): {}", e.getMessage());
            return 0L;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReportPagePayload(
            @JsonProperty("reports") List<ReportWire> reports,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReportWire(
            @JsonProperty("post_public_id") String postPublicId,
            @JsonProperty("post_title") String postTitle,
            @JsonProperty("author_public_id") String authorPublicId,
            @JsonProperty("category") String reason,       // community 응답은 category 필드명 유지, 값은 ReportReason
            @JsonProperty("target_type") String targetType,
            @JsonProperty("status") String status,
            @JsonProperty("report_count") long reportCount,
            @JsonProperty("last_reported_at") LocalDateTime lastReportedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReportStatsPayload(
            @JsonProperty("pending_report_count") long pendingReportCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PostDetailPayload(
            @JsonProperty("post_public_id") String postPublicId,
            @JsonProperty("user_public_id") String userPublicId,
            @JsonProperty("title") String title,
            @JsonProperty("content") String content,
            @JsonProperty("language") String language,
            @JsonProperty("created_at") LocalDateTime createdAt) {
    }
}
