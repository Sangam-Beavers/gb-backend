package com.gb.appadmin.global.config;

import com.gb.common.security.CognitoGroupJwtAuthenticationConverter;
import com.gb.common.security.CognitoJwtDecoderFactory;
import com.gb.common.security.RestAccessDeniedHandler;
import com.gb.common.security.RestAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 방식 B (외부 IdP 검증 전용) 보안 설정.
 * - 공개 경로: /swagger-ui/**, /v3/api-docs/**, /actuator/**, /api/v1/app-admin/app/** (앱 사용자용 읽기)
 * - 관리자 경로: /api/v1/app-admin/admin/** — Cognito {@code admin} 그룹 (cognito:groups=admin) 보유자만
 * - 그 외: 인증 필요
 *
 * <p><b>인가 (RBAC) 는 항상 켠다 (fail-closed):</b> 관리자 경로는 플래그 없이 무조건 {@code hasRole("admin")}
 * 이다 — 어떤 설정/프로파일에서도 관리자 경로가 "인증만 하면 통과" 로 약화되지 않게 한다.
 * {@link CognitoGroupJwtAuthenticationConverter} 가 {@code cognito:groups} 를 {@code ROLE_admin} 으로
 * 승격하고, 인증은 됐으나 그룹이 없으면 {@link RestAccessDeniedHandler} 가 403/COMMON4031로 응답한다.
 *
 * <p>검표원은 {@code issuer-uri} (application-stage.yml) 로 JWT를 검증하며, stage/prod에선
 * {@link CognitoJwtDecoderFactory} 디코더가 {@code token_use=access} 도 요구해 ID 토큰을 거절한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                        .requestMatchers("/api/v1/app-admin/app/**").permitAll()
                        // 관리자 경로 — Cognito admin 그룹 보유자만 (항상 적용).
                        .requestMatchers("/api/v1/app-admin/admin/**").hasRole("admin")
                        .anyRequest().authenticated())
                // 검표원: issuer-uri의 JWKS로 토큰 검증 + cognito:groups → ROLE_ 승격.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new CognitoGroupJwtAuthenticationConverter())))
                // 토큰 누락/무효 → 401/AUTH4011, 그룹 없음 → 403/COMMON4031.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /**
     * Cognito access 토큰 전용 디코더 — stage/prod에서만 만든다 (issuer-uri 필요, token_use=access 검증).
     * dev/test는 이 빈이 없으며, 테스트는 {@code @MockitoBean JwtDecoder} 로 대체한다.
     */
    @Bean
    @Profile({"stage", "prod"})
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        return CognitoJwtDecoderFactory.accessTokenDecoder(issuerUri);
    }
}
