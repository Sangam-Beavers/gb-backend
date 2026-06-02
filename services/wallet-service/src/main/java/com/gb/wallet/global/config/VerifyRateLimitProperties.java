package com.gb.wallet.global.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 계좌 인증(verify) rate-limit 정책. {@code wallet.account.verify-rate-limit.*} 프로퍼티를 바인딩한다.
 *
 * <p>명세에 수치 정의가 없어 외부 설정화한다(하드코딩 지양). 미지정 시 compact 생성자에서 기본값
 * (60초 윈도 / 10회)을 채운다. {@code @ConfigurationPropertiesScan}(WalletServiceApplication)으로
 * 자동 등록되므로 별도 {@code @EnableConfigurationProperties}가 필요 없다.
 *
 * <p>바인딩은 [canonical constructor 실행(null→기본값) → {@code @Validated} 검증] 순이므로, 미지정은
 * 기본값으로 통과하고 명시된 {@code 0}/음수만 거부된다. {@code limit<=0}이면 모든 verify가 차단되고
 * ({@code count<=limit}이 항상 거짓), {@code windowSeconds<=0}이면 카운터 TTL이 깨지므로, 잘못된 값은
 * {@code @Min(1)}으로 기동 시 fail-fast 한다(런타임에 조용히 오작동하지 않게).
 */
@Validated
@ConfigurationProperties(prefix = "wallet.account.verify-rate-limit")
public record VerifyRateLimitProperties(
        @Min(1) Integer windowSeconds,
        @Min(1) Integer limit) {

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