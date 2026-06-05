package com.gb.member.global.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.member.global.exception.code.MemberErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link RealIdpUserClient} 단위 테스트 — 입력 가드 + IdP HTTP 응답 분기 매핑(MEM-10).
 *
 * <p>deactivateUser는 authProviderId null/공백 시 HTTP 전에 fail-fast 하는지, provisionUser/changePassword는
 * IdP가 4xx(입력 문제)→COMMON4001 / 5xx(연동 장애)→COMMON5000으로 매핑하는지 검증한다. HTTP 분기는
 * {@link MockRestServiceServer}로 IdP 응답을 흉내 내며, 스프링 컨텍스트는 띄우지 않는다(순수 단위 테스트).
 */
class RealIdpUserClientTest {

    // 타임아웃 설정은 idpRestClient @Bean(IdpClientConfig)이 담당하고 클라는 RestClient를 주입받는다(MEM1).
    // 테스트는 MockRestServiceServer로 바인딩한 RestClient(builder.build())를 직접 넘겨 HTTP 분기를 흉내 낸다.
    private final RealIdpUserClient client = new RealIdpUserClient(
            RestClient.builder().build(), new ObjectMapper(),
            "http://localhost/dummy/api/v3", "test-admin-token");

    @Test
    @DisplayName("deactivateUser: authProviderId가 null이면 HTTP 호출 전에 COMMON5000으로 fail-fast")
    void deactivateUser_null_failFast() {
        assertThatThrownBy(() -> client.deactivateUser(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("deactivateUser: authProviderId가 공백이면 HTTP 호출 전에 COMMON5000으로 fail-fast")
    void deactivateUser_blank_failFast() {
        assertThatThrownBy(() -> client.deactivateUser("   "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("member-6 — deactivateUser: ?uuid 정확 일치 1건이면 그 pk로 PATCH 비활성화한다")
    void deactivateUser_uuid정확일치_1건_비활성화() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder.build(), new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        String uuid = "11111111-1111-1111-1111-111111111111";
        // 1) GET ?uuid= → uuid가 정확히 일치하는 1건
        server.expect(anything()).andRespond(withSuccess(
                "{\"results\":[{\"pk\":7,\"username\":\"u@x\",\"uuid\":\"" + uuid + "\"}]}",
                MediaType.APPLICATION_JSON));
        // 2) PATCH /core/users/7/ → 200 (이 expect가 잡히면 정확히 그 pk로 호출됐다는 의미)
        server.expect(anything()).andRespond(withSuccess());

        c.deactivateUser(uuid); // 예외 없이 종료

        server.verify(); // GET + PATCH 두 호출 모두 기대대로 일어났다
    }

    @Test
    @DisplayName("member-6 — deactivateUser: ?uuid 정확 일치가 2건 이상이면 COMMON5000(엉뚱한 사용자 비활성화 방어)")
    void deactivateUser_uuid정확일치_2건_COMMON5000() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder.build(), new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        String uuid = "11111111-1111-1111-1111-111111111111";
        // GET ?uuid= 가 같은 uuid 2건을 돌려줌 → 비정상 → 거절(PATCH 미호출: expect를 GET 1개만 둔다).
        server.expect(anything()).andRespond(withSuccess(
                "{\"results\":["
                        + "{\"pk\":7,\"username\":\"a\",\"uuid\":\"" + uuid + "\"},"
                        + "{\"pk\":8,\"username\":\"b\",\"uuid\":\"" + uuid + "\"}]}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> c.deactivateUser(uuid))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("member-6 — deactivateUser: 결과는 있으나 uuid 정확 일치 0건이면 멱등 통과(예외 없음, PATCH 미호출)")
    void deactivateUser_uuid정확일치_0건_멱등통과() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder.build(), new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        String uuid = "11111111-1111-1111-1111-111111111111";
        // 결과는 있으나 요청 uuid와 다른 사용자만 들어 있음(부분일치/엉뚱한 응답) → 대상 없음으로 멱등 통과.
        server.expect(anything()).andRespond(withSuccess(
                "{\"results\":[{\"pk\":9,\"username\":\"other\",\"uuid\":\"99999999-9999-9999-9999-999999999999\"}]}",
                MediaType.APPLICATION_JSON));

        c.deactivateUser(uuid); // 예외 없이 종료(멱등)

        server.verify(); // GET만 일어나고 PATCH는 일어나지 않았다(expect를 1개만 뒀으므로 PATCH 발생 시 실패)
    }

    @Test
    @DisplayName("changePassword: IdP가 4xx 응답이면 COMMON4001(400)로 매핑 — 입력 문제는 서버 오류 아님")
    void changePassword_4xx_COMMON4001() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder.build(), new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        server.expect(anything()).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> c.changePassword("a@example.com", "NewP@ss1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("changePassword: IdP가 5xx 응답이면 COMMON5000(500)로 매핑 — 연동 장애는 서버 측")
    void changePassword_5xx_COMMON5000() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder.build(), new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        server.expect(anything()).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> c.changePassword("a@example.com", "NewP@ss1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("MEM-10 — provisionUser: IdP 사용자 생성이 4xx(이메일/username 충돌 등)면 COMMON4001로 매핑")
    void provisionUser_4xx_COMMON4001() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder.build(), new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        // 첫 호출(POST /core/users/)이 4xx → 입력 문제로 매핑되고 이후 set_password는 호출되지 않는다.
        server.expect(anything()).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> c.provisionUser("a@example.com", "홍길동", "P@ss1", "pub-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("11D member-idp-2 — provisionUser: 4xx 본문이 unique 위반이면 MEMBER4002(409)로 매핑(IdP 고아 진단 가능)")
    void provisionUser_4xx_unique위반_MEMBER4002() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder.build(), new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        // Authentik username(=email) 중복 응답 본문 형태 — 로컬 선점이 먼저 거르므로 이 응답은
        // "IdP에만 사용자가 남은 상태"(프로비저닝 부분실패 고아 등)에서만 나온다.
        server.expect(anything()).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":[\"This field must be unique.\"]}"));

        assertThatThrownBy(() -> c.provisionUser("a@example.com", "홍길동", "P@ss1", "pub-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("MEM-10 — provisionUser: IdP 사용자 생성이 5xx면 COMMON5000으로 매핑(연동 장애)")
    void provisionUser_5xx_COMMON5000() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder.build(), new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        server.expect(anything()).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> c.provisionUser("a@example.com", "홍길동", "P@ss1", "pub-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}
