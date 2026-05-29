package com.gb.member.global.config;

import com.gb.member.global.security.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

// TODO: 인증(JWT) 구현 후, permitAll을 제거하고
//       회원가입/로그인은 permitAll, 나머지는 authenticated()로 교체할 것. 현재는 개발/검증용 전체 허용.
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
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().permitAll());

        return http.build();
    }

    /**
     * Spring Security 표준 인증 흐름의 진입점.
     *
     * <p>{@link DaoAuthenticationProvider}가 {@link CustomUserDetailsService}로 회원을 조회한 뒤
     * 저장된 해시와 입력 비밀번호를 {@link PasswordEncoder}(BCrypt)로 비교한다. 검증 결과는
     * {@code MemberServiceImpl.login()}이 받아 JWT 발급으로 이어간다.
     *
     * <p>실패 시 던지는 {@code BadCredentialsException}/{@code UsernameNotFoundException}은
     * 호출 측에서 AUTH4001로 통일 변환된다(enumeration 방지).
     */
    @Bean
    public AuthenticationManager authenticationManager(
            CustomUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
