package com.gb.wallet.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link RealMemberClient}(wallet)의 HTTP 호출·JWT 릴레이·메서드별 실패 정책 검증.
 *
 * <p>{@link MockRestServiceServer}로 member-service 응답을 모사한다. 핵심(수용 기준 — 정책을 테스트로 못박음):
 * <ul>
 *   <li>getMember = fail-open: 미존재(응답 제외)·5xx·연결 실패·JWT 부재 전부 "Unknown" 폴백, 예외 없음.</li>
 *   <li>findByEmail = fail-fast: 404 본문 code=MEMBER4001만 {@code Optional.empty()}(→호출 측 MEMBER4001),
 *       형식 모를 404·5xx·연결 실패·JWT 부재는 COMMON5000 — 장애가 '없는 회원'으로 둔갑하지 않는다.</li>
 * </ul>
 */
class RealMemberClientTest {

    private static final String BASE_URL = "http://member-api";
    private static final String TOKEN = "relay-token";
    private static final String USER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** SecurityContext에 검증 통과 상태의 JWT를 심는다(OAuth2 Resource Server 통과 후 상태 모사). */
    private static void authenticateWithJwt() {
        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("public_id", "caller-uuid")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private record Fixture(RealMemberClient client, MockRestServiceServer server) {
    }

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new RealMemberClient(builder.build(), BASE_URL), server);
    }

    // ----- getMember (표시용 = fail-open) -----

    @Test
    @DisplayName("getMember: display-info 응답을 6필드 MemberInfo로 매핑한다(email=null — 응답에 없음) + JWT 릴레이")
    void getMember_정상_매핑() {
        authenticateWithJwt();
        Fixture f = fixture();
        String body = """
                { "success": true,
                  "data": { "members": [
                    { "public_id": "%s", "name": "Nguyen Thi Linh", "nickname": "Linh",
                      "nationality": "VN", "is_verified": true } ] },
                  "message": "요청이 성공적으로 처리되었습니다." }
                """.formatted(USER_ID);
        f.server().expect(requestTo(BASE_URL + "/api/v1/members/display-info?public_ids=" + USER_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        MemberInfo result = f.client().getMember(USER_ID);

        assertThat(result).isEqualTo(
                new MemberInfo(USER_ID, null, "Nguyen Thi Linh", "Linh", "VN", true));
        f.server().verify();
    }

    @Test
    @DisplayName("getMember: 미존재·탈퇴(응답 배열에서 제외)는 fallback(publicId echo + Unknown)")
    void getMember_미존재_폴백() {
        authenticateWithJwt();
        Fixture f = fixture();
        String body = """
                { "success": true, "data": { "members": [] }, "message": "ok" }
                """;
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        MemberInfo result = f.client().getMember(USER_ID);

        assertThat(result).isEqualTo(new MemberInfo(USER_ID, null, "Unknown", "Unknown", "UNK", false));
    }

    @Test
    @DisplayName("getMember fail-open: 5xx 응답이면 예외 없이 fallback — 표시 실패가 송금/확인증 본업을 막지 않는다")
    void getMember_5xx_fail_open() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withServerError());

        MemberInfo result = f.client().getMember(USER_ID);

        assertThat(result.nickname()).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("getMember fail-open: 연결 실패도 예외 없이 fallback")
    void getMember_연결실패_fail_open() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withException(new IOException("connection refused")));

        assertThat(f.client().getMember(USER_ID).nickname()).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("getMember fail-open: SecurityContext에 JWT가 없으면 HTTP 호출 없이 fallback")
    void getMember_JWT부재_호출없이_폴백() {
        Fixture f = fixture();
        // 어떤 요청도 기대하지 않는다 — 호출이 발생하면 verify에서 실패.

        assertThat(f.client().getMember(USER_ID).nickname()).isEqualTo("Unknown");
        f.server().verify();
    }

    // ----- findMember (원장 저장용 — 폴백 객체 없음, empty) -----

    @Test
    @DisplayName("findMember: 히트는 present — 6필드 매핑(email=null) + JWT 릴레이")
    void findMember_히트_present() {
        authenticateWithJwt();
        Fixture f = fixture();
        String body = """
                { "success": true,
                  "data": { "members": [
                    { "public_id": "%s", "name": "Nguyen Thi Linh", "nickname": "Linh",
                      "nationality": "VN", "is_verified": true } ] },
                  "message": "ok" }
                """.formatted(USER_ID);
        f.server().expect(requestTo(BASE_URL + "/api/v1/members/display-info?public_ids=" + USER_ID))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(f.client().findMember(USER_ID)).contains(
                new MemberInfo(USER_ID, null, "Nguyen Thi Linh", "Linh", "VN", true));
    }

    @Test
    @DisplayName("findMember: 미존재·탈퇴는 empty — 'Unknown' 폴백 객체를 만들지 않는다(원장에 가짜 이름 영속 방지)")
    void findMember_미존재_empty() {
        authenticateWithJwt();
        Fixture f = fixture();
        String body = """
                { "success": true, "data": { "members": [] }, "message": "ok" }
                """;
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(f.client().findMember(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("findMember: 5xx 장애도 예외 없이 empty — 호출 측은 name=null로 저장하고 본업 진행(명세 §7-1)")
    void findMember_5xx_empty() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withServerError());

        assertThat(f.client().findMember(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("findMember: SecurityContext에 JWT가 없으면 HTTP 호출 없이 empty")
    void findMember_JWT부재_호출없이_empty() {
        Fixture f = fixture();

        assertThat(f.client().findMember(USER_ID)).isEmpty();
        f.server().verify(); // 어떤 요청도 발생하지 않음
    }

    // ----- getMembers (배치, 표시용 = fail-open) -----

    @Test
    @DisplayName("getMembers: 콤마 조인 1회 호출로 배치 조회 — 히트는 매핑, 응답 누락 id는 fallback(계약: 요청한 모든 id 키 포함)")
    void getMembers_배치_매핑_누락은_폴백() {
        authenticateWithJwt();
        Fixture f = fixture();
        String missing = "missing-uuid";
        String body = """
                { "success": true,
                  "data": { "members": [
                    { "public_id": "%s", "name": "Nguyen Thi Linh", "nickname": "Linh",
                      "nationality": "VN", "is_verified": true } ] },
                  "message": "ok" }
                """.formatted(USER_ID);
        // URI 변수 확장이 콤마를 %2C로 인코딩한다 — 서버(서블릿)는 디코딩 후 콤마 split으로 List 바인딩하므로
        // 동작 동일(community RealMemberClient와 같은 wire-format).
        f.server().expect(requestTo(BASE_URL + "/api/v1/members/display-info?public_ids="
                        + USER_ID + "%2C" + missing))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Map<String, MemberInfo> result = f.client().getMembers(List.of(USER_ID, missing));

        assertThat(result).containsOnlyKeys(USER_ID, missing);
        assertThat(result.get(USER_ID)).isEqualTo(
                new MemberInfo(USER_ID, null, "Nguyen Thi Linh", "Linh", "VN", true));
        assertThat(result.get(missing).nickname()).isEqualTo("Unknown"); // 누락분 폴백
        f.server().verify(); // 배치 1회 호출만 발생
    }

    @Test
    @DisplayName("getMembers: 요청하지 않은 id가 응답에 섞여 와도 결과 키에 포함하지 않는다(계약 보존)")
    void getMembers_미요청_id_무시() {
        authenticateWithJwt();
        Fixture f = fixture();
        String body = """
                { "success": true,
                  "data": { "members": [
                    { "public_id": "uninvited-uuid", "name": "X", "nickname": "X",
                      "nationality": "KR", "is_verified": false } ] },
                  "message": "ok" }
                """;
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Map<String, MemberInfo> result = f.client().getMembers(List.of(USER_ID));

        assertThat(result).containsOnlyKeys(USER_ID);
        assertThat(result.get(USER_ID).nickname()).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("getMembers fail-open: chunk 5xx 응답이면 예외 없이 해당 chunk 전원 fallback")
    void getMembers_5xx_fail_open() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withServerError());

        Map<String, MemberInfo> result = f.client().getMembers(List.of(USER_ID, "other-uuid"));

        assertThat(result).containsOnlyKeys(USER_ID, "other-uuid");
        assertThat(result.values()).allMatch(m -> "Unknown".equals(m.nickname()));
    }

    @Test
    @DisplayName("getMembers fail-open: 연결 실패도 예외 없이 전원 fallback")
    void getMembers_연결실패_fail_open() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withException(new IOException("connection refused")));

        Map<String, MemberInfo> result = f.client().getMembers(List.of(USER_ID));

        assertThat(result.get(USER_ID).nickname()).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("getMembers: 101개 요청은 BATCH_LIMIT(100) 단위 2회로 분할 호출된다(§13-1 상한 준수)")
    void getMembers_101개_chunk_분할() {
        authenticateWithJwt();
        Fixture f = fixture();
        List<String> ids = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(i -> "user-" + i)
                .toList();
        String emptyBody = """
                { "success": true, "data": { "members": [] }, "message": "ok" }
                """;
        // 1차 chunk(100개) + 2차 chunk(1개) — 정확히 2회 호출을 기대(검증은 server.verify()).
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withSuccess(emptyBody, MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(BASE_URL + "/api/v1/members/display-info?public_ids=user-101"))
                .andRespond(withSuccess(emptyBody, MediaType.APPLICATION_JSON));

        Map<String, MemberInfo> result = f.client().getMembers(ids);

        assertThat(result).hasSize(101); // 계약: 전원 키 포함(응답이 비어 전원 폴백)
        f.server().verify();
    }

    @Test
    @DisplayName("getMembers fail-open: SecurityContext에 JWT가 없으면 HTTP 호출 없이 전원 fallback")
    void getMembers_JWT부재_호출없이_전원_폴백() {
        Fixture f = fixture();
        // 어떤 요청도 기대하지 않는다 — 호출이 발생하면 verify에서 실패.

        Map<String, MemberInfo> result = f.client().getMembers(List.of(USER_ID, "other-uuid"));

        assertThat(result).containsOnlyKeys(USER_ID, "other-uuid");
        assertThat(result.values()).allMatch(m -> "Unknown".equals(m.nickname()));
        f.server().verify();
    }

    // ----- findByEmail (검증용 = fail-fast) -----

    @Test
    @DisplayName("findByEmail: 200 응답을 MemberInfo로 매핑한다 — email은 조회 키 입력값으로 채움(응답에 없음)")
    void findByEmail_정상_매핑() {
        authenticateWithJwt();
        Fixture f = fixture();
        String body = """
                { "success": true,
                  "data": { "public_id": "%s", "name": "Nguyen Thi Linh", "nickname": "Linh",
                            "nationality": "VN", "is_verified": true },
                  "message": "ok" }
                """.formatted(USER_ID);
        // URI 템플릿 변수는 strict 인코딩된다(@→%40) — 서버(servlet)가 디코딩해 email=linh@example.com으로 받는다.
        f.server().expect(requestTo(BASE_URL + "/api/v1/members/by-email?email=linh%40example.com"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Optional<MemberInfo> result = f.client().findByEmail("linh@example.com");

        assertThat(result).contains(
                new MemberInfo(USER_ID, "linh@example.com", "Nguyen Thi Linh", "Linh", "VN", true));
        f.server().verify();
    }

    @Test
    @DisplayName("findByEmail: 404 + 본문 code=MEMBER4001이면 Optional.empty — 호출 측이 MEMBER4001로 변환")
    void findByEmail_404_MEMBER4001_empty() {
        authenticateWithJwt();
        Fixture f = fixture();
        String errorBody = """
                { "success": false, "code": "MEMBER4001", "message": "존재하지 않는 회원입니다." }
                """;
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/by-email")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorBody));

        assertThat(f.client().findByEmail("ghost@example.com")).isEmpty();
    }

    @Test
    @DisplayName("findByEmail fail-fast: 형식 모를 404(code 없는 본문 — 라우팅 오류 등)는 '없음'으로 단정하지 않고 COMMON5000")
    void findByEmail_형식모를_404_fail_fast() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/by-email")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>Not Found</html>"));

        assertThatThrownBy(() -> f.client().findByEmail("ghost@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("findByEmail fail-fast: 5xx 응답이면 COMMON5000 — 장애를 '없는 회원'으로 오인 금지")
    void findByEmail_5xx_fail_fast() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/by-email")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> f.client().findByEmail("linh@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("findByEmail fail-fast: 연결 실패면 COMMON5000")
    void findByEmail_연결실패_fail_fast() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/by-email")))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> f.client().findByEmail("linh@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("findByEmail fail-fast: SecurityContext에 JWT가 없으면 호출 없이 COMMON5000")
    void findByEmail_JWT부재_fail_fast() {
        Fixture f = fixture();

        assertThatThrownBy(() -> f.client().findByEmail("linh@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
        f.server().verify();
    }
}
