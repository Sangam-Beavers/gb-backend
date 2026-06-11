package com.gb.document;

import com.gb.document.global.client.s3.S3PresignedUrlClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 스프링 컨텍스트 로딩 sanity 테스트.
 *
 * <p>외부(AWS) 클라이언트는 {@link MockitoBean}으로 대체해 실제 호출 없이 컨텍스트가 뜨도록 한다
 * (CLAUDE.md §10). S3PresignedUrlClient는 전 프로파일 공통 Real 구현이지만 presign은 호출 시점에만
 * 자격증명을 요구하므로 컨텍스트 로딩에는 영향이 없고, 여기서는 mock으로 덮는다.
 *
 * <p>방식 B(검표원) 보안 설정은 issuer-uri로 외부 IdP의 JWKS를 받아 {@link JwtDecoder}를 만든다.
 * 테스트엔 실제 IdP가 없으므로 {@code JwtDecoder}를 {@link MockitoBean}으로 대체해 외부 호출을 막는다
 * (member/wallet/community 컨텍스트 테스트와 동일).
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentServiceApplicationTests {

    @MockitoBean
    S3PresignedUrlClient s3PresignedUrlClient;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
    }
}
