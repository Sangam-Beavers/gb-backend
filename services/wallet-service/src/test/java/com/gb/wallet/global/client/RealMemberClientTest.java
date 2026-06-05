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
