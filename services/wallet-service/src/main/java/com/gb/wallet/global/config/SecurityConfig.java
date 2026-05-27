package com.gb.wallet.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// TODO: 인증(Authentik/OIDC) 구현 후, permitAll을 제거하고
//       OAuth2 Resource Server 설정으로 교체할 것. 현재는 개발/검증용 전체 허용.
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
                // 현재는 모든 요청 허용이라 Swagger 경로(/swagger-ui/**, /v3/api-docs/**, /swagger-ui.html)도 통과한다.
                // TODO: 인증 적용 시 Swagger 경로는 인증 예외 처리(permitAll)로 명시하고, 나머지는 authenticated()로 둘 것.
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().permitAll());

        return http.build();
    }
}
