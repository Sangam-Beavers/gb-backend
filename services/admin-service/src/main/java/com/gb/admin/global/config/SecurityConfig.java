package com.gb.admin.global.config;

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
 * 방식 B (외부 IdP 검증 전용) 보안 설정 — 백오피스 콘솔 (모든 경로가 {@code /api/v1/admin/**}).
 *
 * <p>토큰 발급은 외부 IdP (운영/스테이징=Cognito) 가 하고, 이 서비스는 들어온 토큰을 "검표원"으로 검증만 한다.
 * {@code oauth2ResourceServer(jwt)} 가 {@code issuer-uri} (application-stage.yml) 로 Cognito의 공개키 (JWKS) 를
 * 받아 RS256 서명·{@code iss}·{@code exp} 를 검증하고, {@link CognitoJwtDecoderFactory} 가 추가로
 * {@code token_use=access} 를 요구해 ID 토큰을 거절한다. 검증 실패는 {@link RestAuthenticationEntryPoint} 가
 * AUTH4011로 응답한다.
 *
 * <p><b>인가 (RBAC):</b> Cognito {@code admin} 그룹 (토큰 {@code cognito:groups} 클레임, modules/cognito의
 * {@code user_groups} 가 SSOT) 보유자만 콘솔 API에 접근할 수 있다. {@link CognitoGroupJwtAuthenticationConverter}
 * 가 그룹을 {@code ROLE_admin} 으로 승격하고, {@code /api/v1/admin/**} 는 {@code hasRole("admin")} 로 보호한다.
 * 인증은 됐으나 그룹이 없으면 {@link RestAccessDeniedHandler} 가 403/COMMON4031로 응답한다.
 *
 * <p><b>활성 여부는 {@link AdminSecurityPolicy} 가 단일 결정한다 (fail-closed):</b> stage/prod는 항상 인가를
 * 켠다. dev/local은 Cognito {@code cognito:groups} 가 없고 (dev=Authentik) 콘솔이 외부에 노출되지 않으므로
 * (VPN/내부 전용) 기존 무인증 콘솔 (permitAll) 동작을 유지한다. 꺼진 경우 {@code oauth2ResourceServer} 를 아예
 * 걸지 않아 {@code JwtDecoder} (issuer-uri) 없이도 기동한다.
 *
 * <p>문서/헬스체크/메트릭 (Prometheus 스크레이프) 엔드포인트는 인가와 무관하게 공개로 유지한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            AdminSecurityPolicy securityPolicy) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!securityPolicy.isAdminGroupEnforced()) {
            // dev/local 무인증 콘솔 — 기존 동작 유지 (로그인 없음). 외부 노출 경로 없음 (VPN/내부 전용).
            // 검표원을 걸지 않으므로 issuer-uri(JwtDecoder) 없이도 기동한다.
            http
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint));
            return http.build();
        }

        // stage/prod — Cognito 검표원 + admin 그룹 인가.
        http
                .authorizeHttpRequests(auth -> auth
                        // 문서·헬스체크·메트릭은 공개 (Prometheus가 /actuator/prometheus를 스크레이프).
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                        // 콘솔 API 전체 — Cognito admin 그룹 보유자만.
                        .requestMatchers("/api/v1/admin/**").hasRole("admin")
                        // 그 외 (현재 없음) 도 최소 인증 요구 — 신규 경로가 무인가로 새지 않도록.
                        .anyRequest().authenticated())
                // 검표원: JwtDecoder (token_use=access 검증 포함) + cognito:groups → ROLE_ 승격.
                //   ⚠️ entry point를 oauth2ResourceServer DSL "안"에도 건다 (BearerTokenAuthenticationFilter는
                //   토큰 검증 실패 시 자신의 entry point를 쓰므로, 누락 시 AUTH4011 표준 포맷을 벗어난다).
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new CognitoGroupJwtAuthenticationConverter())))
                // 토큰 누락/무효 → 401/AUTH4011 (entry point), 그룹 없음 → 403/COMMON4031 (accessDeniedHandler).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /**
     * Cognito access 토큰 전용 디코더 — stage/prod에서만 만든다 (issuer-uri 필요).
     * dev/test는 이 빈이 없어 (검표원도 미사용) issuer 없이 기동하며, 테스트는 {@code @MockitoBean JwtDecoder} 로 대체한다.
     */
    @Bean
    @Profile({"stage", "prod"})
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        return CognitoJwtDecoderFactory.accessTokenDecoder(issuerUri);
    }
}
