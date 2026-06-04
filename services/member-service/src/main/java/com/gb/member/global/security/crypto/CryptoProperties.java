package com.gb.member.global.security.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AES-256-GCM 컬럼 암호화에 사용할 마스터 키 설정.
 *
 * <p>실 값(Base64 인코딩된 32바이트 = 256bit AES 키)은 환경변수 {@code GB_CRYPTO_KEY}로 주입한다.
 * 운영 yml에 평문으로 적지 않는다(CLAUDE §8 — 비밀은 env로). 키 형식 검증은
 * {@link AesGcmCryptoService} 생성 시점에 fail-fast로 수행한다.
 *
 * <p>application yml 예시:
 * <pre>
 * gb:
 *   crypto:
 *     key: ${GB_CRYPTO_KEY}
 * </pre>
 */
@ConfigurationProperties(prefix = "gb.crypto")
public record CryptoProperties(String key) {
}
