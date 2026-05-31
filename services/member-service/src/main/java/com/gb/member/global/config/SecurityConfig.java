package com.gb.member.global.config;

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
 * <p>로그인은 Authorization Code flow로, 프론트(앱)가 IdP 로그인 페이지에서 직접 수행한다. 백엔드는
 * 로그인에 관여하지 않고(토큰 발급/중계 없음) 들어온 토큰을 검증만 한다. 따라서 비밀번호를 저장·대조하지
 * 않으며 AuthenticationManager/UserDetailsService/PasswordEncoder를 두지 않는다.
 * 회원가입(/auth/register)만 우리 API가 받아 IdP에 사용자를 프로비저닝한다(IdpUserClient).
 *
 * <p>공개 엔드포인트(회원가입/중복확인/Swagger/health)는 permitAll, 나머지는 인증 필요.
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
                        // 인증 불필요(공개) — 회원가입/이메일·비번 재설정.
                        // 로그인·토큰 재발급은 프론트가 IdP와 직접(Authorization Code flow) 하므로 백엔드 엔드포인트가 없다.
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/email/verify-request",
                                "/api/v1/auth/password/**").permitAll()
                        // 가입 전 중복 확인
                        .requestMatchers(
                                "/api/v1/members/check-email",
                                "/api/v1/members/check-nickname").permitAll()
                        // 문서 · 헬스체크
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                        // 그 외 전부 인증 필요(로그아웃, /members/me* 등)
                        .anyRequest().authenticated())
                // 검표원: issuer-uri의 JWKS로 RS256 토큰 검증. 실패 시 AUTH4011.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint));

        return http.build();
    }
}
