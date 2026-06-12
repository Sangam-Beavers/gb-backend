package com.gb.appadmin.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.appadmin.domain.member.dto.response.AppMemberPageResponse;
import com.gb.appadmin.domain.member.dto.response.AppMemberResponse;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * member-service /api/v1/internal/admin/* 호출 실 클라이언트.
 * dev 프로파일엔 {@link MockMemberAdminClient}가 등록되므로 이 빈은 dev가 아닐 때만 활성화.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RealMemberAdminClient implements MemberAdminClient {

    private final RestClient memberRestClient;

    @Override
    public AppMemberPageResponse search(String q, String kycStatus, int page, int size) {
        try {
            String uri = UriComponentsBuilder.fromPath("/api/v1/internal/admin/members")
                    .queryParamIfPresent("q", java.util.Optional.ofNullable(blank(q)))
                    .queryParamIfPresent("kyc_status", java.util.Optional.ofNullable(blank(kycStatus)))
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .build().toUriString();

            Envelope<MemberPagePayload> env = memberRestClient.get().uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            MemberPagePayload payload = env == null ? null : env.data();
            if (payload == null || payload.members() == null) {
                return new AppMemberPageResponse(List.of(), page, size, 0, 0);
            }
            List<AppMemberResponse> members = payload.members().stream()
                    .map(m -> new AppMemberResponse(
                            m.userPublicId(), m.email(), m.name(), m.nickname(),
                            m.nationality(),
                            m.status() != null ? m.status() : "ACTIVE",
                            m.kycStatus() != null ? m.kycStatus() : "NOT_SUBMITTED",
                            m.communityBanned(),
                            m.joinedAt() != null ? m.joinedAt().toString() : null))
                    .collect(Collectors.toList());
            return new AppMemberPageResponse(members, payload.page(), payload.size(),
                    payload.totalElements(), payload.totalPages());
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] search 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void changeStatus(String userPublicId, String status) {
        try {
            memberRestClient.patch()
                    .uri("/api/v1/internal/admin/members/{id}/status?status={status}",
                            userPublicId, status)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] changeStatus 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void setCommunityBan(String userPublicId, boolean banned) {
        try {
            memberRestClient.patch()
                    .uri("/api/v1/internal/admin/members/{id}/community-ban?banned={banned}",
                            userPublicId, banned)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] setCommunityBan 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Optional<AppMemberResponse> getMemberByPublicId(String userPublicId) {
        try {
            Envelope<MemberWire> env = memberRestClient.get()
                    .uri("/api/v1/internal/admin/members/{id}", userPublicId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            MemberWire m = env == null ? null : env.data();
            if (m == null) return Optional.empty();
            return Optional.of(new AppMemberResponse(
                    m.userPublicId(), m.email(), m.name(), m.nickname(),
                    m.nationality(),
                    m.status() != null ? m.status() : "ACTIVE",
                    m.kycStatus() != null ? m.kycStatus() : "NOT_SUBMITTED",
                    m.communityBanned(),
                    m.joinedAt() != null ? m.joinedAt().toString() : null));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            log.error("[RealMemberAdminClient] getMemberByPublicId 실패 id={}: {}", userPublicId, e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        } catch (RuntimeException e) {
            log.error("[RealMemberAdminClient] getMemberByPublicId 실패 id={}: {}", userPublicId, e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ── wire DTOs ─────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope<T>(@JsonProperty("data") T data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemberPagePayload(
            @JsonProperty("members") List<MemberWire> members,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemberWire(
            @JsonProperty("user_public_id") String userPublicId,
            @JsonProperty("email") String email,
            @JsonProperty("name") String name,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("nationality") String nationality,
            @JsonProperty("status") String status,
            @JsonProperty("kyc_status") String kycStatus,
            @JsonProperty("community_banned") boolean communityBanned,
            @JsonProperty("joined_at") java.time.LocalDateTime joinedAt) {}
}
