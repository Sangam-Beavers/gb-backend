package com.gb.document.global.config;

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
 * "검표원"으로 검증만 한다. {@code oauth2ResourceServer(jwt)}가 {@code issuer-uri}(AUTH_ISSUER_URI,
 * application-{env}.yml)로 IdP의 공개키(JWKS)를 받아 RS256 서명·만료를 자동 검증한다. 검증 실패는
 * {@link RestAuthenticationEntryPoint}가 AUTH4011 표준 포맷으로 응답한다.
 *
 * <p>본인 식별자(userPublicId)는 토큰 custom claim {@code public_id}에서 추출한다
 * ({@link com.gb.document.global.security.CurrentUserPublicId} 참고). 인증 미구현 시절의
 * {@code X-User-Public-Id} 헤더 임시 처리를 대체한다(member/wallet/community와 동일 전환 — CLAUDE.md §9).
 *
 * <p>모든 document 비즈니스 엔드포인트(제출/상태/결과/목록/재요청/챗봇)는 인증 필요. 문서/헬스체크만 공개.
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
                        // 문서 · 헬스체크만 공개. 그 외 document 엔드포인트는 전부 인증 필요.
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                        // TODO(다음 스프린트): mTLS·NetworkPolicy로 격리. 현재는 admin-service가 JWT 없이
                        //   호출할 수 있도록 임시 permitAll. 외부 노출 금지 — Ingress에서 /internal 경로 차단.
                        .requestMatchers("/api/v1/internal/admin/**").permitAll()
                        .anyRequest().authenticated())
                // 검표원: issuer-uri의 JWKS로 RS256 토큰 검증. 실패(만료·위조·서명 불일치) 시 AUTH4011.
                //   ⚠️ entry point를 oauth2ResourceServer DSL "안"에도 건다. BearerTokenAuthenticationFilter는
                //   토큰 검증 실패 시 exceptionHandling의 entry point가 아니라 자신의 entry point를 쓰므로,
                //   여기 명시하지 않으면 만료/위조 토큰이 Spring 기본(빈 body + WWW-Authenticate)으로 응답돼
                //   AUTH4011 표준 포맷을 벗어난다. (토큰 누락은 exceptionHandling 경로라 아래도 함께 둔다.)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> {}))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint));

        return http.build();
    }
}
