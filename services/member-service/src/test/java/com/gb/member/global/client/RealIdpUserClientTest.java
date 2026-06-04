package com.gb.member.global.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link RealIdpUserClient#deactivateUser} 입력 가드 단위 테스트.
 *
 * <p>authProviderId가 null/공백이면 {@code "?uuid="} 빈 요청을 보내지 않고 HTTP 호출 전에
 * fail-fast 하는지 검증한다. 가드가 먼저 throw하므로 실제 HTTP는 타지 않아 더미 base URI/토큰으로 구성한다
 * (스프링 컨텍스트 불필요 — 순수 단위 테스트).
 */
class RealIdpUserClientTest {

    private final RealIdpUserClient client = new RealIdpUserClient(
            RestClient.builder(), new ObjectMapper(),
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
    @DisplayName("changePassword: IdP가 4xx 응답이면 COMMON4001(400)로 매핑 — 입력 문제는 서버 오류 아님")
    void changePassword_4xx_COMMON4001() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RealIdpUserClient c = new RealIdpUserClient(
                builder, new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
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
                builder, new ObjectMapper(), "http://localhost/dummy/api/v3", "test-admin-token");
        server.expect(anything()).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> c.changePassword("a@example.com", "NewP@ss1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}
