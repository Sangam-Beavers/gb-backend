package com.gb.wallet.global.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 송금 PIN 검증(/pin-verify) rate-limit 정책. {@code wallet.transfer.pin-verify-rate-limit.*}를 바인딩한다.
 *
 * <p><b>왜 전용 정책인가(wallet-pin-redis-1):</b> 계좌 인증/예금주 조회는 {@link VerifyRateLimitProperties}를
 * 공유하지만(둘 다 외부 은행 PII/인증 폭주 방어라 같은 강도가 적절), PIN 검증은 <b>로컬 비밀(6자리 PIN)의
 * 무차별 대입 방어</b>라는 별개의 보안 관심사다. 같은 props를 공유하면 계좌-verify 정책을 조정할 때 PIN
 * throttle이 함께 바뀌어(의도치 않은 보안 약화) 위험하므로, 독립 정책으로 둔다.
 *
 * <p>미지정 시 compact 생성자에서 기본값(60초 윈도 / 10회)을 채운다. {@code @ConfigurationPropertiesScan}
 * (WalletServiceApplication)으로 자동 등록된다. {@code limit<=0}/{@code windowSeconds<=0}은
 * {@code @Min(1)}로 기동 시 fail-fast 한다(VerifyRateLimitProperties와 동일 사상).
 */
@Validated
@ConfigurationProperties(prefix = "wallet.transfer.pin-verify-rate-limit")
public record PinVerifyRateLimitProperties(
        @Min(1) Integer windowSeconds,
        @Min(1) Integer limit) {

    /** 운영 정책 확정 전 기본 윈도/임계값. PIN 단기 잠금(5회/10분)보다 약간 느슨하되 버스트를 캡한다. */
    private static final int DEFAULT_WINDOW_SECONDS = 60;
    private static final int DEFAULT_LIMIT = 10;

    public PinVerifyRateLimitProperties {
        if (windowSeconds == null) {
            windowSeconds = DEFAULT_WINDOW_SECONDS;
        }
        if (limit == null) {
            limit = DEFAULT_LIMIT;
        }
    }
}
