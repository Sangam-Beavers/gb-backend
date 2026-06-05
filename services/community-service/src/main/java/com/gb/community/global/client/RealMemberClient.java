package com.gb.community.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 실제 member-service의 표시정보 배치 조회({@code GET /api/v1/members/display-info?public_ids=...},
 * 명세 auth §13-1)를 호출하는 {@link MemberClient} 구현체.
 *
 * <p>방식 B(외부 IdP) 기반이라 member-service도 OAuth2 Resource Server로 토큰을 검증한다. 따라서
 * 본 클라이언트는 <b>현재 요청의 JWT 토큰을 그대로 member-service에 릴레이</b>한다 —
 * {@code SecurityContextHolder}의 {@link JwtAuthenticationToken}에서 {@code tokenValue}를 꺼내
 * {@code Authorization: Bearer}로 부착한다(member-service {@code RealWalletClient}와 동일 패턴.
 * 별도 service token 없음).
 *
 * <p><b>실패 정책 = fail-open(표시용):</b> 게시글/댓글의 작성자 닉네임·인증배지는 표시 보조 데이터라,
 * HTTP 장애·5xx·토큰 부재 등 어떤 실패에도 예외를 올리지 않고 {@link #FALLBACK}("Unknown")으로
 * degrade한다(WARN 로그). 본문 쓰기가 이미 커밋된 댓글 작성 응답이 표시정보 실패로 5xx가 되어
 * 클라이언트 재시도 → 중복 댓글이 쌓이는 것을 막기 위함(CommentServiceImpl.createComment 주석 참고).
 *
 * <p>배치 계약(인터페이스 javadoc): {@link #getMembers}는 <b>요청한 모든 id를 키로 포함</b>한다 —
 * 응답에서 빠진 id(미존재·탈퇴)와 호출 실패분은 FALLBACK으로 채운다.
 *
 * <p>활성 프로파일이 {@code dev}/{@code test}가 아닐 때만 빈으로 등록된다(stage·prod에서 동작).
 * dev에서는 {@link DevMemberClient}가 fixture 미스를 본 클래스 인스턴스(빈 아님)에 위임한다.
 */
@Slf4j
@Component
@Profile("!dev & !test")
public class RealMemberClient implements MemberClient {

    /** 미존재·탈퇴·조회 실패 시 안전 표시용 기본값(기존 MockMemberClient FALLBACK과 동일 형태). */
    static final MemberInfo FALLBACK = new MemberInfo("Unknown", false);

    /** display-info 배치 API의 public_ids 개수 상한(명세 auth §13-1). 초과 요청은 chunk로 나눠 호출한다. */
    private static final int BATCH_LIMIT = 100;

    private final RestClient restClient;
    private final String memberApiBaseUrl;

    public RealMemberClient(
            RestClient memberRestClient,
            @Value("${member.api.base-url}") String memberApiBaseUrl) {
        this.restClient = memberRestClient;
        this.memberApiBaseUrl = memberApiBaseUrl;
    }

    @Override
    public MemberInfo getMember(String userPublicId) {
        // 단건도 배치 API 1건 호출로 처리 — member-service 엔드포인트를 하나로 유지(HTTP 로직 1벌).
        return getMembers(List.of(userPublicId)).get(userPublicId);
    }

    @Override
    public Map<String, MemberInfo> getMembers(Collection<String> userPublicIds) {
        List<String> ids = userPublicIds.stream().distinct().toList();
        // 계약: 요청한 모든 id를 키로 포함. FALLBACK으로 선채움하고 조회 성공분만 덮어쓴다 —
        // 부분 실패(chunk 일부 실패)·응답 누락 id가 자연스럽게 fail-open으로 수렴한다.
        Map<String, MemberInfo> result = new HashMap<>();
        ids.forEach(id -> result.put(id, FALLBACK));
        if (ids.isEmpty()) {
            return result;
        }

        String bearerToken = currentJwtBearer();
        if (bearerToken == null) {
            // 인증 컨텍스트 없이 호출됨(비정상 경로) — 표시용이라 fail-open으로 전원 폴백.
            log.warn("[RealMemberClient] SecurityContext에 JWT가 없어 표시정보를 조회하지 못했습니다. ids={}", ids.size());
            return result;
        }

        for (int from = 0; from < ids.size(); from += BATCH_LIMIT) {
            List<String> chunk = ids.subList(from, Math.min(from + BATCH_LIMIT, ids.size()));
            fetchChunkInto(result, chunk, bearerToken);
        }
        return result;
    }

    /** chunk 1개를 호출해 성공분만 result에 덮어쓴다. 실패는 fail-open(해당 chunk 전원 FALLBACK 유지). */
    private void fetchChunkInto(Map<String, MemberInfo> result, List<String> chunk, String bearerToken) {
        try {
            DisplayInfoEnvelope envelope = restClient.get()
                    .uri(memberApiBaseUrl + "/api/v1/members/display-info?public_ids={ids}",
                            String.join(",", chunk))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(DisplayInfoEnvelope.class);
            if (envelope == null || envelope.data() == null || envelope.data().members() == null) {
                log.warn("[RealMemberClient] display-info 응답 형식이 비었습니다 — 폴백 유지. chunk={}", chunk.size());
                return;
            }
            for (DisplayInfoMember member : envelope.data().members()) {
                // 요청하지 않은 id가 섞여 와도 계약(요청 id만 키)을 지키도록 기존 키만 덮어쓴다.
                if (member.publicId() != null && result.containsKey(member.publicId())) {
                    result.put(member.publicId(), new MemberInfo(member.nickname(), member.isVerified()));
                }
            }
        } catch (RuntimeException e) {
            // 응답 4xx/5xx(RestClientResponseException)·연결 실패·역직렬화 실패 등 전부 fail-open —
            // 표시용 조회 실패가 본업(게시글/댓글 응답)을 5xx로 만들지 않는다(wallet fetchMemberNameSafe와
            // 동일하게 RuntimeException 전체를 흡수).
            log.warn("[RealMemberClient] display-info 호출 실패 — \"Unknown\" 폴백. chunk={}, msg={}",
                    chunk.size(), e.getMessage());
        }
    }

    /**
     * 현재 요청의 SecurityContext에서 JWT 토큰 문자열을 꺼낸다(있으면). 없으면 null.
     * member-service {@code RealWalletClient#currentJwtBearer()}와 동일 — 원본 JWT를 그대로 릴레이해
     * member-service가 재검증한다.
     */
    private String currentJwtBearer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }
        return null;
    }

    // ----- member-service 응답 wire-format DTO -----
    // 외부(타 서비스) 응답 매핑은 전역 SNAKE_CASE 설정에 기대지 않고 @JsonProperty로 명시 고정한다
    // (MockBankClient 어댑터 규칙). 커뮤니티가 소비하지 않는 필드(name/nationality 등)는 선언하지 않고
    // @JsonIgnoreProperties로 무시한다(불필요 PII는 파싱조차 하지 않음).

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DisplayInfoEnvelope(
            @JsonProperty("success") boolean success,
            @JsonProperty("data") DisplayInfoData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DisplayInfoData(
            @JsonProperty("members") List<DisplayInfoMember> members) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DisplayInfoMember(
            @JsonProperty("public_id") String publicId,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("is_verified") boolean isVerified) {
    }
}
