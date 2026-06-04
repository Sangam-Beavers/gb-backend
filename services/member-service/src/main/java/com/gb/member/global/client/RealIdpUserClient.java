package com.gb.member.global.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
 * <p>IdP 응답 에러는 상태로 분기한다: 4xx(이메일/username 충돌 등 클라이언트 입력 문제)는
 * {@link CommonErrorCode#INVALID_REQUEST}(400), 5xx·연결 실패(서버측 연동 장애)는
 * {@link CommonErrorCode#INTERNAL_SERVER_ERROR}(500)로 변환한다(CLAUDE §6).
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

    /**
     * connect/read 타임아웃이 설정된 {@code idpRestClient} 빈을 주입받는다(IdpClientConfig — MEM1).
     * 타임아웃 없는 raw 빌더를 직접 build()하면 IdP 지연 시 가입/탈퇴가 DB 락을 보유한 채 무한 대기해
     * Hikari 풀이 고갈된다.
     */
    public RealIdpUserClient(
            RestClient idpRestClient,
            ObjectMapper objectMapper,
            @Value("${auth.idp.api-base-uri}") String apiBaseUri,
            @Value("${auth.idp.admin-token}") String adminToken) {
        this.restClient = idpRestClient;
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

        } catch (RestClientResponseException e) {
            // IdP 응답 에러 — 4xx(이메일/username 충돌 등 입력 문제)→COMMON4001, 5xx→COMMON5000.
            log.error("Authentik 사용자 프로비저닝 실패: email={}, status={}, msg={}",
                    email, e.getStatusCode(), e.getMessage());
            throw new BusinessException(idpStatusToError(e));
        } catch (RestClientException e) {
            // 연결 실패·타임아웃 등(응답 없음) → 서버측 연동 장애 → COMMON5000.
            log.error("Authentik 사용자 프로비저닝 연결 실패: email={}, msg={}", email, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void changePassword(String email, String newPassword) {
        try {
            // 1) email(=username)으로 사용자 조회 → pk 확보. Authentik: GET /core/users/?username={username}
            //    email은 URI 템플릿 변수로 넘겨 자동 인코딩한다(@, + 등 특수문자 안전 — 문자열 직접 결합 금지).
            UserListResponse list = restClient.get()
                    .uri(apiBaseUri + "/core/users/?username={username}", email)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .retrieve()
                    .body(UserListResponse.class);

            if (list == null || list.results() == null) {
                log.error("Authentik 사용자 조회 실패(비번 변경): email={}", email);
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }

            // ?username 필터가 부분일치/동명을 돌려줄 수 있으므로, username이 "정확히" 일치하는 1건만 사용한다.
            // (엉뚱한 사용자의 비밀번호를 바꾸지 않도록 방어 — 0건 또는 2건 이상이면 거절)
            List<UserEntry> matched = list.results().stream()
                    .filter(u -> email.equals(u.username()) && u.pk() != null)
                    .toList();
            if (matched.size() != 1) {
                log.error("Authentik username 정확 일치가 1건이 아님(비번 변경): email={}, count={}",
                        email, matched.size());
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }

            // 2) 그 pk로 비밀번호 설정(204).
            restClient.post()
                    .uri(apiBaseUri + "/core/users/" + matched.get(0).pk() + "/set_password/")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(Map.of("password", newPassword)))
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientResponseException e) {
            log.error("Authentik 비밀번호 변경 실패: email={}, status={}, msg={}",
                    email, e.getStatusCode(), e.getMessage());
            throw new BusinessException(idpStatusToError(e));
        } catch (RestClientException e) {
            log.error("Authentik 비밀번호 변경 연결 실패: email={}, msg={}", email, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 탈퇴 처리: 저장된 user uuid({@code authProviderId})로 사용자를 찾아 {@code is_active=false}로 비활성화한다.
     *
     * <p>Authentik 상세/수정 API는 정수 {@code pk} 기준이라 두 번에 나눠 호출한다(API 구조상):
     * <ol>
     *   <li>{@code GET /core/users/?uuid={authProviderId}} — uuid로 사용자를 찾아 정수 {@code pk}를 얻는다.
     *       UsersFilter의 {@code uuid = UUIDFilter}라 표준 하이픈 UUID를 그대로 받는다(하이픈 제거 불필요).
     *       결과가 비면(이미 없는 사용자) 멱등 통과 — 로그만 남기고 정상 종료한다(팀 결정).</li>
     *   <li>{@code PATCH /core/users/{pk}/} body {@code {"is_active": false}} — 비활성화(200).</li>
     * </ol>
     *
     * <p>IdP 응답 4xx는 {@link CommonErrorCode#INVALID_REQUEST}(400), 5xx·연결 실패는
     * {@link CommonErrorCode#INTERNAL_SERVER_ERROR}(500)로 변환한다. 어느 쪽이든 BusinessException이라
     * Service에서 이 예외가 올라오면 @Transactional이 롤백되어 로컬 soft delete도 반영되지 않는다(정합성, 지시서 §F).
     *
     * <p>TODO: 가입 시 {@code pk}도 함께 저장해 두면 탈퇴 때 조회 1회(GET)를 줄일 수 있으나
     *          스키마 변경이라 본 작업 범위 밖이다(지시서 §D-2).
     */
    @Override
    public void deactivateUser(String authProviderId) {
        // authProviderId가 비면 "?uuid=" 빈 요청이 나가므로 HTTP 호출 전에 fail-fast.
        // 활성 회원인데 IdP 식별자가 없으면 가입 프로비저닝이 깨진 상태(서버측 데이터 정합성 문제)다.
        if (authProviderId == null || authProviderId.isBlank()) {
            log.error("IdP 비활성화 불가: authProviderId가 비어 있습니다(가입 프로비저닝 누락 의심).");
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        try {
            // 1) uuid → pk 해석. uuid 템플릿 변수는 RestClient가 안전하게 인코딩한다.
            UserListResponse list = restClient.get()
                    .uri(apiBaseUri + "/core/users/?uuid={uuid}", authProviderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .retrieve()
                    .body(UserListResponse.class);

            if (list == null || list.results() == null || list.results().isEmpty()) {
                // 대상이 IdP에 없음(이미 삭제 등). 멱등 통과 — 로컬 soft delete는 정상 진행된다.
                log.warn("Authentik 비활성화 대상 사용자 없음(멱등 통과). uuid={}", authProviderId);
                return;
            }

            // ?uuid 필터가 부분일치/엉뚱한 사용자를 돌려줄 수 있으므로, uuid가 "정확히" 일치하는 1건만 쓴다
            // (엉뚱한 사용자를 비활성화하지 않도록 방어 — changePassword와 동일 패턴, member-6). 정확 일치 0건은
            // 대상 없음으로 보고 멱등 통과, 2건 이상이면 비정상이라 거절한다.
            List<UserEntry> matched = list.results().stream()
                    .filter(u -> authProviderId.equals(u.uuid()) && u.pk() != null)
                    .toList();
            if (matched.isEmpty()) {
                log.warn("Authentik uuid 정확 일치 대상 없음(멱등 통과). uuid={}", authProviderId);
                return;
            }
            if (matched.size() > 1) {
                log.error("Authentik uuid 정확 일치가 2건 이상(비활성화): uuid={}, count={}",
                        authProviderId, matched.size());
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            Integer pk = matched.get(0).pk();

            // 2) is_active=false로 비활성화. 본문은 ObjectMapper로 직접 JSON 문자열화(컨버터 환경차 회피).
            restClient.patch()
                    .uri(apiBaseUri + "/core/users/{pk}/", pk)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(Map.of("is_active", false)))
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientResponseException e) {
            // IdP 응답 에러 — 4xx→COMMON4001, 5xx→COMMON5000. 어느 쪽이든 BusinessException이라 Service의
            // @Transactional이 롤백돼 로컬 soft delete도 반영되지 않는다(IdP에서 계속 로그인 가능 방지).
            log.error("Authentik 사용자 비활성화 실패: uuid={}, status={}, msg={}",
                    authProviderId, e.getStatusCode(), e.getMessage());
            throw new BusinessException(idpStatusToError(e));
        } catch (RestClientException e) {
            // 연결 실패·타임아웃 등(응답 없음) → COMMON5000 → @Transactional 롤백.
            log.error("Authentik 사용자 비활성화 연결 실패: uuid={}, msg={}", authProviderId, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * IdP 응답 상태를 본체 에러로 매핑한다: 4xx(클라이언트 입력·충돌 등) → COMMON4001(400),
     * 그 외(5xx 등 서버측) → COMMON5000(500). 연결 실패(응답 없음)는 호출부에서 별도로 COMMON5000 처리.
     */
    private CommonErrorCode idpStatusToError(RestClientResponseException e) {
        return e.getStatusCode().is4xxClientError()
                ? CommonErrorCode.INVALID_REQUEST
                : CommonErrorCode.INTERNAL_SERVER_ERROR;
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

    /** 사용자 목록 조회 응답(필요한 필드만). username/uuid 필터로 찾은 사용자의 pk·username을 쓴다. */
    private record UserListResponse(@JsonProperty("results") List<UserEntry> results) {
    }

    /**
     * 목록 항목(필요한 필드만). changePassword는 {@code username} 정확 일치 확인에, deactivateUser는 {@code uuid}
     * 정확 일치 확인에 쓰고, 일치한 항목의 {@code pk}로 후속 PATCH/POST를 호출한다(member-6 — 첫 건 맹신 방지).
     */
    private record UserEntry(
            @JsonProperty("pk") Integer pk,
            @JsonProperty("username") String username,
            @JsonProperty("uuid") String uuid) {
    }
}
