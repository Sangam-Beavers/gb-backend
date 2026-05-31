package com.gb.member;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 스프링 컨텍스트가 정상적으로 뜨는지만 확인하는 스모크 테스트.
 *
 * <p>방식 B(검표원) 보안 설정은 issuer-uri로 외부 IdP의 JWKS를 받아 {@link JwtDecoder}를 만든다.
 * 테스트엔 실제 IdP가 없으므로 {@code JwtDecoder}를 {@link MockitoBean}으로 대체해 외부 호출을 막는다
 * (CLAUDE §10: @SpringBootTest는 @ActiveProfiles("test") + 외부 의존 @MockitoBean).
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberServiceApplicationTests {

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void contextLoads() {
	}

}
