package com.gb.wallet.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 송금 PIN 해싱용 {@link PasswordEncoder} 빈.
 *
 * <p>방식 B에서 계정 비밀번호는 IdP가 보유하므로 인증용 PasswordEncoder는 없었으나, 송금 PIN(별도 PIN)은
 * 우리가 직접 저장·검증하므로 BCrypt 인코더가 필요하다. 평문 PIN은 저장하지 않고 해시만 보관하며,
 * 검증은 {@code passwordEncoder.matches(rawPin, hash)}로 한다.
 *
 * <p>의존성은 기존 {@code spring-boot-starter-security}에 포함된 spring-security-crypto를 쓴다(추가 의존성 없음).
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
