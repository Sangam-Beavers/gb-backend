package com.gb.community;

import com.gb.community.global.client.MemberClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 전체 Spring 컨텍스트 로딩 스모크 테스트.
 *
 * <p>test 프로파일을 활성화해 {@code application-test.yml}의 H2 datasource를 사용한다.
 * {@code MemberClient} 구현체는 test 프로파일에서 등록되지 않으므로({@code DevMemberClient}=dev,
 * {@code RealMemberClient}=!dev & !test) 테스트에서는 인터페이스를 {@code @MockitoBean}으로 가린다.
 *
 * <p>방식 B(검표원) 보안 설정은 issuer-uri로 외부 IdP의 JWKS를 받아 {@link JwtDecoder}를 만든다.
 * 테스트엔 실제 IdP가 없으므로 {@code JwtDecoder}를 {@link MockitoBean}으로 대체해 외부 호출을 막는다
 * (member-service 컨텍스트 테스트와 동일 패턴).
 */
@SpringBootTest
@ActiveProfiles("test")
class CommunityServiceApplicationTests {

    @MockitoBean
    private MemberClient memberClient;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
    }
}