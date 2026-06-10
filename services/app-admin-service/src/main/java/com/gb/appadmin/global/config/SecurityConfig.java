package com.gb.appadmin.global.config;

import com.gb.common.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 방식 B(외부 IdP 검증 전용) 보안 설정.
 * 개발 편의를 위해 현재 anyRequest().permitAll() 상태. 발표 후 JWT 검증 + authenticated()로 원복.
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
                        // TODO: 발표 후 원복 — JWT 검증 활성화와 함께 아래 두 줄 주석 해제, permitAll 제거.
                        // .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                        // .anyRequest().authenticated())
                        .anyRequest().permitAll())
                // TODO: 발표 후 원복 — AUTH_ISSUER_URI 환경변수 확보 후 아래 블록 주석 해제.
                // .oauth2ResourceServer(oauth2 -> oauth2
                //         .authenticationEntryPoint(authenticationEntryPoint)
                //         .jwt(jwt -> {}))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint));

        return http.build();
    }
}
