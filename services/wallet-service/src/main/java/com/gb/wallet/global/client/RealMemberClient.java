package com.gb.wallet.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 실제 member-service의 표시정보 조회 API(명세 auth §13)를 호출하는 {@link MemberClient} 구현체.
 * <ul>
 *   <li>{@link #getMembers}/{@link #getMember} → {@code GET /api/v1/members/display-info?public_ids=...}
 *       (§13-1, 단건도 배치 API 경유 — HTTP 로직 1벌)</li>
 *   <li>{@link #findByEmail} → {@code GET /api/v1/members/by-email?email={email}} (§13-2)</li>
 * </ul>
 *
 * <p>방식 B(외부 IdP) 기반이라 member-service도 OAuth2 Resource Server로 토큰을 검증한다. 따라서
 * <b>현재 요청의 JWT를 그대로 릴레이</b>한다({@code SecurityContextHolder} →
 * {@code Authorization: Bearer}. member-service {@code RealWalletClient}와 동일 패턴, 별도 service token 없음).
 *
 * <p><b>실패 정책이 메서드별로 다르다(인터페이스 계약·CLAUDE §7):</b>
 * <ul>
 *   <li>{@link #getMember}/{@link #getMembers} = <b>fail-open(표시용)</b> — 최근 송금 수신자 표시·확인증
 *       본명 채움 용도라, 미존재·탈퇴는 물론 HTTP 장애·5xx·토큰 부재에도 예외 없이 fallback("Unknown")으로
 *       degrade한다(WARN). 배치는 요청한 모든 id를 키로 선채움 후 성공분만 덮어써 부분 실패도 폴백으로 수렴.
 *       단 {@code email}은 display-info 응답에 없으므로 항상 null이다(소비처 없음 — 명세 §13 PII 최소화).</li>
 *   <li>{@link #findByEmail} = <b>fail-fast(검증용)</b> — validate-member(송금 수신자 검증)가 "없으면
 *       없다"를 신뢰해야 하므로, 404 MEMBER4001 응답만 {@link Optional#empty()}로 매핑하고 그 외
 *       모든 실패(5xx·연결 실패·형식 모를 404·토큰 부재)는 COMMON5000으로 던진다(조용한 가짜 데이터 금지 —
 *       장애를 '없는 회원'으로 오인해 송금 흐름이 잘못 진행되는 것을 차단).</li>
 * </ul>
 *
 * <p>활성 프로파일이 {@code dev}/{@code test}가 아닐 때만 빈으로 등록된다(<b>stage·prod</b>에서 동작 —
 * 기존 MockMemberClient가 stage를 점유하던 것에서 stage가 Real로 바뀌는 의도된 변경).
 * dev에서는 {@link DevMemberClient}가 fixture 미스를 본 클래스 인스턴스(빈 아님)에 위임한다.
 */
@Slf4j
@Component
@Profile("!dev & !test")
public class RealMemberClient implements MemberClient {

    private final RestClient restClient;
    private final String memberApiBaseUrl;

    public RealMemberClient(
            RestClient memberRestClient,
            @Value("${member.api.base-url}") String memberApiBaseUrl) {
        this.restClient = memberRestClient;
        this.memberApiBaseUrl = memberApiBaseUrl;
    }

    /** 미존재·탈퇴·조회 실패 시 안전 표시용 기본값(기존 MockMemberClient fallback과 동일 — publicId echo). */
    static MemberInfo fallback(String userPublicId) {
        return new MemberInfo(userPublicId, null, "Unknown", "Unknown", "UNK", false);
    }

    /** display-info의 public_ids 1회 호출 상한(auth §13-1). 초과분은 chunk로 분할 호출한다. */
    private static final int BATCH_LIMIT = 100;

    @Override
    public MemberInfo getMember(String userPublicId) {
        // 단건도 배치 API 1건 호출로 처리 — member-service 엔드포인트를 하나로 유지(HTTP 로직 1벌, community 미러).
        return getMembers(List.of(userPublicId)).get(userPublicId);
    }

    @Override
    public Map<String, MemberInfo> getMembers(Collection<String> userPublicIds) {
        List<String> ids = userPublicIds.stream().distinct().toList();
        // 계약: 요청한 모든 id를 키로 포함. fallback으로 선채움하고 조회 성공분만 덮어쓴다 —
        // 부분 실패(chunk 일부 실패)·응답 누락 id가 자연스럽게 fail-open으로 수렴한다(community 미러).
        Map<String, MemberInfo> result = new HashMap<>();
        ids.forEach(id -> result.put(id, fallback(id)));
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

    @Override
    public Optional<MemberInfo> findMember(String userPublicId) {
        // 원장 저장용 — fallback 객체를 만들지 않는다. 성공 히트만 present, 미존재·장애·JWT 부재는 empty
        // (예외 없음: 확인증/등록 본업은 이름 없이도 진행돼야 하고, 가짜 문자열("Unknown")이 영속되면 안 된다).
        String bearerToken = currentJwtBearer();
        if (bearerToken == null) {
            log.warn("[RealMemberClient] SecurityContext에 JWT가 없어 원장용 표시정보를 조회하지 못했습니다. user_public_id={}",
                    userPublicId);
            return Optional.empty();
        }
        try {
            DisplayInfoEnvelope envelope = restClient.get()
                    .uri(memberApiBaseUrl + "/api/v1/members/display-info?public_ids={ids}", userPublicId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(DisplayInfoEnvelope.class);
            if (envelope != null && envelope.data() != null && envelope.data().members() != null) {
                for (MemberDisplayPayload member : envelope.data().members()) {
                    if (userPublicId.equals(member.publicId())) {
                        return Optional.of(new MemberInfo(member.publicId(), null, member.name(),
                                member.nickname(), member.nationality(), member.isVerified()));
                    }
                }
            }
            return Optional.empty(); // 미존재·탈퇴(응답 배열에서 제외) — 이름 없음으로 처리.
        } catch (RuntimeException e) {
            log.warn("[RealMemberClient] display-info(원장용) 호출 실패 — empty 처리. user_public_id={}, msg={}",
                    userPublicId, e.getMessage());
            return Optional.empty();
        }
    }

    /** chunk 1개를 호출해 성공분만 result에 덮어쓴다. 실패는 fail-open(해당 chunk 전원 fallback 유지). */
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
            for (MemberDisplayPayload member : envelope.data().members()) {
                // 요청하지 않은 id가 섞여 와도 계약(요청 id만 키)을 지키도록 기존 키만 덮어쓴다.
                if (member.publicId() != null && result.containsKey(member.publicId())) {
                    // email은 display-info 응답에 없어 null — 어떤 호출 측도 getMember(s) 결과의 email을
                    // 소비하지 않음(validate 경로는 findByEmail이 채움 — 명세 §13 PII 최소화).
                    result.put(member.publicId(), new MemberInfo(member.publicId(), null, member.name(),
                            member.nickname(), member.nationality(), member.isVerified()));
                }
            }
        } catch (RuntimeException e) {
            // 응답 4xx/5xx·연결 실패·역직렬화 실패 전부 fail-open — 표시 실패가 송금/확인증 본업을 막지 않는다
            // (TransferServiceImpl.fetchMemberNameSafe의 RuntimeException 흡수와 같은 정책을 클라이언트 계층에서 보장).
            log.warn("[RealMemberClient] display-info 호출 실패 — \"Unknown\" 폴백. chunk={}, msg={}",
                    chunk.size(), e.getMessage());
        }
    }

    @Override
    public Optional<MemberInfo> findByEmail(String email) {
        String bearerToken = currentJwtBearer();
        if (bearerToken == null) {
            // 검증 용도는 fail-fast — 인증 컨텍스트 부재를 '없는 회원'으로 오인하면 안 된다.
            log.error("[RealMemberClient] SecurityContext에 JWT가 없어 회원 검증을 수행할 수 없습니다.");
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        try {
            ByEmailEnvelope envelope = restClient.get()
                    .uri(memberApiBaseUrl + "/api/v1/members/by-email?email={email}", email)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(ByEmailEnvelope.class);
            if (envelope == null || envelope.data() == null) {
                log.error("[RealMemberClient] by-email 응답 형식이 비었습니다(2xx인데 data 없음).");
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            MemberDisplayPayload member = envelope.data();
            // email은 응답에 없으므로(명세 §13 — PII 최소화) 조회 키로 쓴 입력값을 그대로 채운다.
            return Optional.of(new MemberInfo(member.publicId(), email, member.name(),
                    member.nickname(), member.nationality(), member.isVerified()));
        } catch (RestClientResponseException e) {
            // 404 중에서도 본문 code가 MEMBER4001인 응답만 "없는 회원"이다 — 경로 오류·게이트웨이 404 등
            // 형식 모를 404를 '없음'으로 단정하면 장애가 미존재로 둔갑하므로 그 외는 전부 fail-fast.
            if (e.getStatusCode().value() == 404 && isMemberNotFound(e)) {
                return Optional.empty();
            }
            log.error("[RealMemberClient] by-email 응답 에러: status={}, msg={}", e.getStatusCode(), e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        } catch (RestClientException e) {
            // 연결 실패·타임아웃 등(응답 없음).
            log.error("[RealMemberClient] by-email 연결 실패: msg={}", e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }

    /** 에러 본문(공통 실패 Envelope)의 code가 MEMBER4001(존재하지 않는 회원)인지 판별한다. */
    private static boolean isMemberNotFound(RestClientResponseException e) {
        try {
            ErrorBody body = e.getResponseBodyAs(ErrorBody.class);
            return body != null && "MEMBER4001".equals(body.code());
        } catch (RuntimeException parseFailure) {
            // 본문이 우리 실패 Envelope 형식이 아님(JSON 아님 등) — '없는 회원'으로 단정하지 않는다.
            return false;
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
    // (MockBankClient 어댑터 규칙). 미선언 필드는 @JsonIgnoreProperties로 무시.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DisplayInfoEnvelope(
            @JsonProperty("success") boolean success,
            @JsonProperty("data") DisplayInfoData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DisplayInfoData(
            @JsonProperty("members") List<MemberDisplayPayload> members) {
    }

    /** by-email(§13-2)은 data가 표시정보 단건 — display-info의 members[] 항목과 동일 필드. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ByEmailEnvelope(
            @JsonProperty("success") boolean success,
            @JsonProperty("data") MemberDisplayPayload data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemberDisplayPayload(
            @JsonProperty("public_id") String publicId,
            @JsonProperty("name") String name,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("nationality") String nationality,
            @JsonProperty("is_verified") boolean isVerified) {
    }

    /** 공통 실패 Envelope({@code success:false, code, message}) — 404 MEMBER4001 판별용. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(
            @JsonProperty("success") boolean success,
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {
    }
}
