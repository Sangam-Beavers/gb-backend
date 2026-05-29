package com.gb.document.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// TODO: 인증(Authentik/OIDC) 구현 후, permitAll을 제거하고
//       OAuth2 Resource Server 설정으로 교체할 것. 현재는 개발/검증용 전체 허용.
//       (wallet-service.SecurityConfig 패턴과 동일)
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 현재는 모든 요청 허용이라 Swagger 경로(/swagger-ui/**, /v3/api-docs/**)도 통과.
                // TODO: 인증 적용 시 Swagger 경로는 permitAll로 명시, 나머지는 authenticated()로 둘 것.
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().permitAll());

        return http.build();
    }
}
