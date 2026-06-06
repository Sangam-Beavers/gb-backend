package com.gb.community.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link RealMemberClient}의 HTTP 호출·JWT 릴레이·fail-open(표시용) 정책 검증.
 *
 * <p>{@link MockRestServiceServer}로 member-service display-info 응답을 모사한다(실서버 불필요).
 * 핵심: ① 정상 응답 매핑(+응답에 없는 미존재·탈퇴 id는 FALLBACK — 배치 계약), ② 5xx/연결 실패가
 * 예외로 새지 않고 전원 "Unknown" 폴백(fail-open — 커밋된 댓글 작성 응답을 5xx로 만들지 않음),
 * ③ SecurityContext의 JWT가 Authorization: Bearer로 릴레이됨, ④ JWT 부재 시 호출 없이 폴백,
 * ⑤ 100개 초과 입력의 chunk 분할(명세 §13-1 상한 준수).
 */
class RealMemberClientTest {

    private static final String BASE_URL = "http://member-api";
    private static final String TOKEN = "relay-token";

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

    @Test
    @DisplayName("getMembers: 200 응답을 매핑하고, 응답에 없는 id(미존재·탈퇴)는 FALLBACK으로 채운다(배치 계약) + JWT 릴레이")
    void getMembers_정상_매핑_누락은_폴백() {
        authenticateWithJwt();
        Fixture f = fixture();
        // 실제 member-service 응답 형태 그대로(envelope + 커뮤니티가 안 쓰는 name/nationality 포함) —
        // 선언하지 않은 필드는 @JsonIgnoreProperties로 무시되는지 함께 검증한다.
        String body = """
                { "success": true,
                  "data": { "members": [
                    { "public_id": "pub-1", "name": "Nguyen Thi Linh", "nickname": "Linh",
                      "nationality": "VN", "is_verified": true } ] },
                  "message": "요청이 성공적으로 처리되었습니다." }
                """;
        f.server().expect(requestTo(BASE_URL + "/api/v1/members/display-info?public_ids=pub-1%2Cpub-gone"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Map<String, MemberInfo> result = f.client().getMembers(List.of("pub-1", "pub-gone"));

        assertThat(result).containsOnlyKeys("pub-1", "pub-gone");
        assertThat(result.get("pub-1")).isEqualTo(new MemberInfo("Linh", true));
        assertThat(result.get("pub-gone")).isEqualTo(RealMemberClient.FALLBACK);
        f.server().verify();
    }

    @Test
    @DisplayName("getMember: 단건도 display-info 배치 API 1회 호출로 처리한다")
    void getMember_단건_배치API_경유() {
        authenticateWithJwt();
        Fixture f = fixture();
        String body = """
                { "success": true,
                  "data": { "members": [
                    { "public_id": "pub-1", "nickname": "Linh", "is_verified": false } ] },
                  "message": "ok" }
                """;
        f.server().expect(requestTo(BASE_URL + "/api/v1/members/display-info?public_ids=pub-1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        MemberInfo result = f.client().getMember("pub-1");

        assertThat(result).isEqualTo(new MemberInfo("Linh", false));
        f.server().verify();
    }

    @Test
    @DisplayName("fail-open: 5xx 응답이면 예외 없이 전원 \"Unknown\" 폴백(표시용 — 본업 응답을 5xx로 만들지 않음)")
    void getMembers_5xx_fail_open() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withServerError());

        Map<String, MemberInfo> result = f.client().getMembers(List.of("pub-1", "pub-2"));

        assertThat(result).containsOnlyKeys("pub-1", "pub-2");
        assertThat(result.values()).allMatch(RealMemberClient.FALLBACK::equals);
        // HTTP 호출이 실제로 발생했는지 단언 — 없으면 "호출 없이 폴백"(JWT부재 경로와 동일 결과)이어도
        // 통과하는 false positive가 된다(5xx를 "받고" fail-open했는지가 검증 대상).
        f.server().verify();
    }

    @Test
    @DisplayName("fail-open: 연결 실패(IOException)도 예외 없이 \"Unknown\" 폴백")
    void getMembers_연결실패_fail_open() {
        authenticateWithJwt();
        Fixture f = fixture();
        f.server().expect(requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withException(new IOException("connection refused")));

        Map<String, MemberInfo> result = f.client().getMembers(List.of("pub-1"));

        assertThat(result.get("pub-1")).isEqualTo(RealMemberClient.FALLBACK);
        f.server().verify(); // 연결 실패를 "겪고" 폴백했는지 — 호출 자체가 없었어도 통과하는 것 방지
    }

    @Test
    @DisplayName("fail-open: SecurityContext에 JWT가 없으면 HTTP 호출 없이 전원 폴백")
    void getMembers_JWT부재_호출없이_폴백() {
        // 인증 컨텍스트 미설정(authenticateWithJwt 호출 안 함).
        Fixture f = fixture();
        // 어떤 요청도 기대하지 않는다 — 호출이 발생하면 MockRestServiceServer가 AssertionError를 던진다.

        Map<String, MemberInfo> result = f.client().getMembers(List.of("pub-1", "pub-2"));

        assertThat(result).containsOnlyKeys("pub-1", "pub-2");
        assertThat(result.values()).allMatch(RealMemberClient.FALLBACK::equals);
        f.server().verify();
    }

    @Test
    @DisplayName("배치 상한: 100개 초과 입력은 chunk로 나눠 호출한다(명세 §13-1 public_ids ≤ 100)")
    void getMembers_100개_초과_chunk_분할() {
        authenticateWithJwt();
        Fixture f = fixture();
        String emptyBody = """
                { "success": true, "data": { "members": [] }, "message": "ok" }
                """;
        f.server().expect(ExpectedCount.times(2),
                        requestTo(startsWith(BASE_URL + "/api/v1/members/display-info")))
                .andRespond(withSuccess(emptyBody, MediaType.APPLICATION_JSON));

        List<String> ids = IntStream.rangeClosed(1, 101).mapToObj(i -> "pub-" + i).toList();
        Map<String, MemberInfo> result = f.client().getMembers(ids);

        assertThat(result).hasSize(101);
        f.server().verify(); // 정확히 2회 호출(100 + 1)
    }
}
