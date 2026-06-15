package com.gb.common.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Cognito access 토큰 전용 {@link JwtDecoder} 팩토리 — 기본 검증 (서명·{@code iss}·{@code exp}) 위에
 * {@code token_use == "access"} 를 더해 ID 토큰을 거절한다.
 *
 * <p>왜 필요한가: Cognito는 access·id 토큰을 같은 풀 키로 서명하고 {@code iss} 도 동일하므로, issuer만 보는
 * 기본 검증은 ID 토큰도 통과시킨다. 두 토큰의 {@code cognito:groups} 가 항상 같다는 가정에 인가를 묶지 않도록,
 * AWS 권고대로 access 토큰만 받아들인다 (관리자 표면 한정 하드닝). dev (Authentik) 토큰엔 {@code token_use} 가
 * 없으므로 이 디코더는 Cognito (stage/prod) 경로에서만 쓴다.
 *
 * <p>{@code client_id} (대상 클라이언트) 검증은 현재 풀에 SPA 클라이언트가 하나뿐이라 보류한다 — 두 번째
 * 클라이언트 추가 시 허용 목록을 설정으로 주입해 함께 검증할 것 (follow-up).
 */
public final class CognitoJwtDecoderFactory {

    /** Cognito access 토큰의 {@code token_use} 클레임 값. */
    public static final String TOKEN_USE_CLAIM = "token_use";
    public static final String TOKEN_USE_ACCESS = "access";

    private CognitoJwtDecoderFactory() {
    }

    /** issuer의 JWKS로 RS256·iss·exp를 검증하고, 추가로 {@code token_use=access} 를 요구하는 디코더. */
    public static JwtDecoder accessTokenDecoder(String issuerUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                accessTokenUseValidator()));
        return decoder;
    }

    /** {@code token_use} 가 정확히 {@code "access"} 인지 검증한다 (누락·다른 값 → 실패 → 401). */
    public static OAuth2TokenValidator<Jwt> accessTokenUseValidator() {
        return new JwtClaimValidator<String>(TOKEN_USE_CLAIM, TOKEN_USE_ACCESS::equals);
    }
}
