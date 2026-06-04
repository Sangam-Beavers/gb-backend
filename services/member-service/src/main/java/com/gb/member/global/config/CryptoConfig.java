package com.gb.member.global.config;

import com.gb.member.global.security.crypto.CryptoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 컬럼 암호화용 {@link CryptoProperties} 빈을 활성화한다.
 *
 * <p>메인 애플리케이션에 {@code @ConfigurationPropertiesScan}을 안 붙여도 이 한 곳에서 명시적으로
 * 등록되어 부팅 시 {@code gb.crypto.key} 환경 변수가 바인딩된다.
 */
@Configuration
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {
}
