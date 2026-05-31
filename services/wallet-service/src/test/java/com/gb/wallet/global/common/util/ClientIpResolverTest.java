package com.gb.wallet.global.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link ClientIpResolver}의 순수 함수 동작 검증.
 *
 * <p>{@link MockHttpServletRequest}로 헤더 유무·다중 IP·공백 케이스를 결정적으로 검증한다
 * (Mockito 목 없이 spring-test 제공).
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("X-Forwarded-For 단일 IP가 있으면 그 IP를 반환한다(getRemoteAddr보다 우선)")
    void xff_단일() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Forwarded-For 다중 IP면 맨 앞(최초 클라이언트) IP를 trim해서 반환한다")
    void xff_다중() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Forwarded-For 헤더가 없으면 getRemoteAddr()로 fallback한다")
    void xff_없음() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("X-Forwarded-For가 빈/공백 문자열이면 getRemoteAddr()로 fallback한다")
    void xff_빈값() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "   ");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.1");
    }
}
