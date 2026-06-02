package com.gb.member.global.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 실제 외부 IdP(개발=Authentik)의 관리 API를 호출해 회원을 등록하는 구현체.
 *
 * <p>Authentik 회원 등록은 두 번의 호출로 이뤄진다(API가 그렇게 나뉘어 있음):
 * <ol>
 *   <li>{@code POST /api/v3/core/users/} — 사용자 생성(username/email/name + attributes.public_id).
 *       응답에서 {@code pk}와 {@code uuid}를 받는다.</li>
 *   <li>{@code POST /api/v3/core/users/{pk}/set_password/} — 1에서 받은 pk의 사용자에 비밀번호 설정(204).</li>
 * </ol>
 *
 * <p>사용자 생성 시 우리 {@code publicId}를 {@code attributes.public_id}로 함께 저장한다. Authentik
 * Provider에 이 attribute를 토큰 claim({@code public_id})으로 내보내는 Scope/Property Mapping을 설정하면,
 * 발급 토큰에서 publicId를 바로 꺼낼 수 있다(토큰 sub ↔ publicId 매핑).
 *
 * <p>이 호출은 로그인 중계와 달리 <b>관리자 토큰</b>(Authorization: Bearer)이 필요하다.
 * 토큰은 평문 금지: {@code auth.idp.admin-token}으로 받되 실제 값은 환경변수(AUTH_ADMIN_TOKEN)로만 주입한다.
 *
 * <p>실패는 모두 통합 변환한다: 어떤 단계든 IdP가 거절·실패하면 가입을 진행할 수 없으므로
 * {@link CommonErrorCode#INTERNAL_SERVER_ERROR}로 변환한다(연동 장애는 서버 측 문제로 취급 — CLAUDE §6).
 *
 * <p>주의(설정 의존): 반환하는 {@code uuid}가 로그인 토큰의 {@code sub}와 일치하려면 Authentik
 * Provider의 subject mode를 "Based on the User's UUID"로 맞춰야 한다. 기본값(hashed id)이면
 * 토큰 sub가 uuid와 달라 토큰→회원 매핑이 어긋난다. 로그인(Authorization Code flow) 후 검표원을
 * 통과한 토큰의 sub로 SecurityContext에서 회원을 찾을 때 이 값이 일치해야 한다.
 */
@Slf4j
@Component
public class RealIdpUserClient implements IdpUserClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUri;
    private final String adminToken;

    public RealIdpUserClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${auth.idp.api-base-uri}") String apiBaseUri,
            @Value("${auth.idp.admin-token}") String adminToken) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.apiBaseUri = apiBaseUri;
        this.adminToken = adminToken;
    }

    @Override
    public String provisionUser(String email, String name, String rawPassword, String publicId) {
        try {
            // 1) 사용자 생성. username은 이메일로 통일(Authentik에서 username은 필수·고유).
            // 본문은 ObjectMapper로 직접 JSON 문자열로 만들어 보낸다(컨버터 환경 차이로 Map이
            // 빈 본문으로 직렬화되는 문제를 피하기 위해 — String은 항상 그대로 전송된다).
            // attributes.public_id: 우리 회원 식별자를 IdP에 저장 → 토큰 custom claim(public_id)으로 노출.
            CreateUserResponse created = restClient.post()
                    .uri(apiBaseUri + "/core/users/")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(Map.of(
                            "username", email,
                            "email", email,
                            "name", name,
                            "type", "internal",
                            "is_active", true,
                            "attributes", Map.of("public_id", publicId))))
                    .retrieve()
                    .body(CreateUserResponse.class);

            if (created == null || created.pk() == null || created.uuid() == null) {
                log.error("Authentik 사용자 생성 응답이 비어 있습니다. email={}", email);
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }

            // 2) 비밀번호 설정(별도 엔드포인트). 성공 시 204.
            restClient.post()
                    .uri(apiBaseUri + "/core/users/" + created.pk() + "/set_password/")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(Map.of("password", rawPassword)))
                    .retrieve()
                    .toBodilessEntity();

            return created.uuid();

        } catch (RestClientException e) {
            // 4xx(이메일/username 충돌 등)·연결 실패·5xx 모두 포함. 가입 진행 불가 → 통합 변환.
            log.error("Authentik 사용자 프로비저닝 실패: email={}, msg={}", email, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void changePassword(String email, String newPassword) {
        try {
            // 1) email(=username)으로 사용자 조회 → pk 확보. Authentik: GET /core/users/?username={email}
            UserListResponse list = restClient.get()
                    .uri(apiBaseUri + "/core/users/?username=" + email)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .retrieve()
                    .body(UserListResponse.class);

            if (list == null || list.results() == null || list.results().isEmpty()
                    || list.results().get(0).pk() == null) {
                // IdP에 해당 사용자가 없음 — 우리 DB엔 있는데 IdP엔 없는 비정상이거나 잘못된 이메일.
                log.error("Authentik 사용자 조회 실패(비번 변경): email={}", email);
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }

            // 2) 그 pk로 비밀번호 설정(204).
            restClient.post()
                    .uri(apiBaseUri + "/core/users/" + list.results().get(0).pk() + "/set_password/")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(Map.of("password", newPassword)))
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientException e) {
            log.error("Authentik 비밀번호 변경 실패: email={}, msg={}", email, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /** 요청 본문 맵을 JSON 문자열로 직렬화한다. 실패는 연동 불가이므로 COMMON5000으로 변환. */
    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error("Authentik 요청 본문 직렬화 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /** 사용자 생성 응답(필요한 필드만). pk는 비밀번호 설정 URL에, uuid는 authProviderId 저장에 쓴다. */
    private record CreateUserResponse(
            @JsonProperty("pk") Integer pk,
            @JsonProperty("uuid") String uuid) {
    }

    /** 사용자 목록 조회 응답(필요한 필드만). username 필터로 조회 시 results[0].pk를 쓴다. */
    private record UserListResponse(
            @JsonProperty("results") java.util.List<UserItem> results) {
    }

    private record UserItem(@JsonProperty("pk") Integer pk) {
    }
}
