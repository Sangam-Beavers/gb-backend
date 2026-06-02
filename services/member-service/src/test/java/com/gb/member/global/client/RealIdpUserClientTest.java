package com.gb.member.global.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
