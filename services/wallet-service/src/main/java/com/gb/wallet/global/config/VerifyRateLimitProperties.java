package com.gb.wallet.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 계좌 인증(verify) rate-limit 정책. {@code wallet.account.verify-rate-limit.*} 프로퍼티를 바인딩한다.
 *
 * <p>명세에 수치 정의가 없어 외부 설정화한다(하드코딩 지양). 미지정 시 compact 생성자에서 기본값
 * (60초 윈도 / 10회)을 채운다. {@code @ConfigurationPropertiesScan}(WalletServiceApplication)으로
 * 자동 등록되므로 별도 {@code @EnableConfigurationProperties}가 필요 없다.
 */
@ConfigurationProperties(prefix = "wallet.account.verify-rate-limit")
public record VerifyRateLimitProperties(Integer windowSeconds, Integer limit) {

    /** 운영 정책 확정 전 기본 윈도/임계값. */
    private static final int DEFAULT_WINDOW_SECONDS = 60;
    private static final int DEFAULT_LIMIT = 10;

    public VerifyRateLimitProperties {
        if (windowSeconds == null) {
            windowSeconds = DEFAULT_WINDOW_SECONDS;
        }
        if (limit == null) {
            limit = DEFAULT_LIMIT;
        }
    }
}