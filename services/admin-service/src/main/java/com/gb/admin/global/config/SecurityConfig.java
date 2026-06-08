package com.gb.admin.global.config;

import com.gb.common.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 방식 B(외부 IdP 검증 전용) 보안 설정.
 *
 * <p>토큰 발급은 외부 IdP(개발=Authentik, 운영/스테이징=Cognito)가 하고, 이 서비스는 들어온 토큰을
 * "검표원"으로 검증만 한다. {@code oauth2ResourceServer(jwt)}가 {@code issuer-uri}(application-{env}.yml)로
 * IdP의 공개키(JWKS)를 받아 RS256 서명·만료를 자동 검증한다. 검증 실패는
 * {@link RestAuthenticationEntryPoint}가 AUTH4011 표준 포맷으로 응답한다.
 *
 * <p>본인 식별자(adminPublicId)는 토큰 custom claim {@code public_id}에서 추출한다
 * ({@link com.gb.admin.global.security.CurrentAdminPublicId} 참고).
 *
 * <p>Phase 1은 group claim(SUPER/CS/COMPLIANCE/FINANCE) 검사를 하지 않고
 * {@code .authenticated()}로만 보호한다 — group 기반 RBAC는 다음 스프린트. 문서/헬스체크/메트릭 엔드포인트는
 * 공개(Prometheus 스크레이프가 가능해야 하므로 actuator permitAll 유지).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // TODO: 발표 후 원복 — 다음 스프린트에서 RBAC(admin group claim 검사) 도입과 함께
                        // 아래 두 줄 주석 해제, 그 아래 `.anyRequest().permitAll()` 줄 제거.
                        // .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                        // .anyRequest().authenticated())
                        .anyRequest().permitAll())
                // TODO: 발표 후 원복 — AUTH_ISSUER_URI 환경변수 확보 후 아래 oauth2ResourceServer 블록과
                // application-dev.yml의 spring.security.oauth2.resourceserver 블록을 함께 주석 해제하면
                // JWT(RS256/JWKS) 검증이 다시 활성화된다. permitAll 토글도 동시에 원복할 것.
                // .oauth2ResourceServer(oauth2 -> oauth2
                //         .authenticationEntryPoint(authenticationEntryPoint)
                //         .jwt(jwt -> {}))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint));

        return http.build();
    }
}
