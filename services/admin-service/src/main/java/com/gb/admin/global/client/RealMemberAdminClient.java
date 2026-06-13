package com.gb.admin.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.admin.global.config.InternalApiProperties;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
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

/**
 * member-service /api/v1/internal/admin/* 를 호출하는 실 클라이언트.
 *
 * <p>admin-service 는 OAuth2 비활성이라 JWT 가 없다 — 호출 시 인증 헤더 없이 보낸다(도메인 측 permitAll).
 * 다음 스프린트에 mTLS·NetworkPolicy 로 격리 + admin 토큰 릴레이 도입.
 *
 * <p>활성 프로파일이 {@code mock-clients} 가 아닐 때만 빈으로 등록된다(기본 Real).
 */
@Slf4j
@Component
@Profile("!mock-clients")
public class RealMemberAdminClient implements MemberAdminClient {

    private final RestClient restClient;
    private final String baseUrl;

    public RealMemberAdminClient(RestClient internalApiRestClient, InternalApiProperties props) {
        this.restClient = internalApiRestClient;
        Map<String, String> urls = props.urls();
        this.baseUrl = urls == null ? null : urls.get("member");
        if (this.baseUrl == null || this.baseUrl.isBlank()) {
            throw new IllegalStateException("admin.internal-api.urls.member 가 비어 있습니다 — 환경변수 MEMBER_INTERNAL_URL 확인.");
        }
    }

    @Override
    public Page<AdminMemberSummary> search(String q, KycStatus kycStatus, int page, int size) {
        try {
            String uri = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/internal/admin/members")
                    .queryParamIfPresent("q", java.util.Optional.ofNullable(blankToNull(q)))
                    .queryParamIfPresent("kyc_status", java.util.Optional.ofNullable(kycStatus == null ? null : kycStatus.name()))
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .build().toUriString();
            InternalApiEnvelope<MemberPagePayload> envelope = restClient.get().uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<MemberPagePayload>>() {});
            MemberPagePayload payload = envelope == null ? null : envelope.data();
            if (payload == null || payload.members() == null) {
                return new PageImpl<>(List.of(), PageRequest.of(page, Math.max(size, 1)), 0);
            }
            List<AdminMemberSummary> mapped = payload.members().stream()
                    .map(RealMemberAdminClient::toSummary)
                    .collect(Collectors.toList());
            return new PageImpl<>(mapped, PageRequest.of(payload.page(), Math.max(payload.size(), 1)),
                    payload.totalElements());
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] search 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void approveKyc(String userPublicId, String adminPublicId) {
        try {
            restClient.post()
                    .uri(baseUrl + "/api/v1/internal/admin/members/{id}/kyc/approve", userPublicId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] approveKyc 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void rejectKyc(String userPublicId, String adminPublicId, String reason) {
        try {
            restClient.post()
                    .uri(baseUrl + "/api/v1/internal/admin/members/{id}/kyc/reject", userPublicId)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of("reason", reason == null ? "" : reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] rejectKyc 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Map<String, AdminMemberMini> lookup(Collection<String> userPublicIds) {
        Map<String, AdminMemberMini> result = new LinkedHashMap<>();
        if (userPublicIds == null || userPublicIds.isEmpty()) return result;
        try {
            String ids = userPublicIds.stream().filter(s -> s != null && !s.isBlank()).distinct()
                    .limit(100).collect(Collectors.joining(","));
            if (ids.isEmpty()) return result;
            InternalApiEnvelope<LookupPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/members/lookup?user_public_ids={ids}", ids)
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<LookupPayload>>() {});
            if (env == null || env.data() == null || env.data().members() == null) return result;
            env.data().members().forEach((k, v) -> result.put(k,
                    new AdminMemberMini(v.userPublicId(), v.email(), v.nickname(), v.nationality())));
            return result;
        } catch (RuntimeException e) {
            // 표시용 enrichment 라 fail-open — 호출 측이 "Unknown" 폴백.
            log.warn("[RealMemberAdminClient] lookup 실패(fail-open): {}", e.getMessage());
            return result;
        }
    }

    @Override
    public AdminMemberStats stats() {
        try {
            InternalApiEnvelope<MemberStatsPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/stats/members")
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<MemberStatsPayload>>() {});
            if (env == null || env.data() == null) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            MemberStatsPayload p = env.data();
            return new AdminMemberStats(p.totalMembers(), p.pendingKycCount(), p.approvedKycCount(),
                    p.rejectedKycCount(), p.kycPassRate(), p.newMembersToday());
        } catch (BusinessException be) {
            throw be;
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] stats 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public AdminMemberDemographics demographics() {
        try {
            InternalApiEnvelope<DemographicsPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/stats/demographics")
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<DemographicsPayload>>() {});
            if (env == null || env.data() == null) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            DemographicsPayload p = env.data();
            return new AdminMemberDemographics(
                    toBuckets(p.genderDistribution()),
                    toBuckets(p.ageDistribution()),
                    toBuckets(p.nationalityDistribution()));
        } catch (BusinessException be) {
            throw be;
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] demographics 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static List<AdminMemberDemographics.Bucket> toBuckets(List<BucketWire> wires) {
        if (wires == null) return List.of();
        return wires.stream()
                .map(b -> new AdminMemberDemographics.Bucket(b.key(), b.count()))
                .collect(Collectors.toList());
    }

    private static AdminMemberSummary toSummary(MemberWire m) {
        KycStatus kyc;
        try {
            kyc = m.kycStatus() == null ? KycStatus.PENDING : KycStatus.valueOf(m.kycStatus());
        } catch (IllegalArgumentException e) {
            // NOT_SUBMITTED 등 admin enum 에 없는 값 → PENDING 으로 폴백(발표 안정성).
            kyc = KycStatus.PENDING;
        }
        return new AdminMemberSummary(
                m.userPublicId(),
                m.email(),
                m.name(),
                m.nickname(),
                m.nationality(),
                kyc,
                m.identityDocumentType(),
                m.joinedAt());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ===== wire DTO =====
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemberPagePayload(
            @JsonProperty("members") List<MemberWire> members,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemberWire(
            @JsonProperty("user_public_id") String userPublicId,
            @JsonProperty("email") String email,
            @JsonProperty("name") String name,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("nationality") String nationality,
            @JsonProperty("kyc_status") String kycStatus,
            @JsonProperty("identity_document_type") String identityDocumentType,
            @JsonProperty("identity_document_number_masked") String identityDocumentNumberMasked,
            @JsonProperty("joined_at") LocalDateTime joinedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LookupPayload(@JsonProperty("members") Map<String, MemberMiniWire> members) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemberMiniWire(
            @JsonProperty("user_public_id") String userPublicId,
            @JsonProperty("email") String email,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("nationality") String nationality) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemberStatsPayload(
            @JsonProperty("total_members") long totalMembers,
            @JsonProperty("pending_kyc_count") long pendingKycCount,
            @JsonProperty("approved_kyc_count") long approvedKycCount,
            @JsonProperty("rejected_kyc_count") long rejectedKycCount,
            @JsonProperty("kyc_pass_rate") String kycPassRate,
            @JsonProperty("new_members_today") long newMembersToday) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DemographicsPayload(
            @JsonProperty("gender_distribution") List<BucketWire> genderDistribution,
            @JsonProperty("age_distribution") List<BucketWire> ageDistribution,
            @JsonProperty("nationality_distribution") List<BucketWire> nationalityDistribution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BucketWire(
            @JsonProperty("key") String key,
            @JsonProperty("count") long count) {
    }
}
