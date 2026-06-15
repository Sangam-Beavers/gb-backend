package com.gb.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link CognitoJwtDecoderFactory#accessTokenUseValidator()} 검증 — Cognito access 토큰만 통과하고
 * ID 토큰 ({@code token_use=id}) ·누락은 거절하는지 본다 (네트워크 없는 단위 검증).
 */
class CognitoJwtDecoderFactoryTest {

    private final OAuth2TokenValidator<Jwt> validator = CognitoJwtDecoderFactory.accessTokenUseValidator();

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("sub");
    }

    @Test
    @DisplayName("token_use=access → 통과")
    void access_통과() {
        Jwt jwt = baseJwt().claim("token_use", "access").build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("token_use=id (ID 토큰) → 거절")
    void id토큰_거절() {
        Jwt jwt = baseJwt().claim("token_use", "id").build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    @DisplayName("token_use 누락 → 거절")
    void 누락_거절() {
        Jwt jwt = baseJwt().build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }
}
