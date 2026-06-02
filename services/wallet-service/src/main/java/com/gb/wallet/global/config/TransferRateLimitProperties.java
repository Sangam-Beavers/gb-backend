package com.gb.wallet.global.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 송금(transfer.execute) rate-limit 정책. {@code wallet.transfer.rate-limit.*} 프로퍼티를 바인딩한다.
 *
 * <p>외부 자금 이동(REMITTANCE)·내부 송금(INTERNAL_TRANSFER) 공통 진입 시 사용자(public_id) 단위
 * 고정 윈도 rate-limit. 계좌 인증({@link VerifyRateLimitProperties}, IP 단위)과 달리 송금은 인증된
 * 사용자가 호출하므로 user 단위 카운터가 더 적합하다(같은 사용자의 폭주 차단).
 *
 * <p>{@code @ConfigurationPropertiesScan}으로 자동 등록되므로 별도 {@code @EnableConfigurationProperties}
 * 가 필요 없다. 미지정 시 canonical 생성자에서 기본값(60초 윈도 / 30회)을 채운다 — 송금은 verify보다
 * 임계값을 더 너그럽게 두는 게 일반적 정책(사용자가 화면에서 여러 번 시도하는 경우 정상).
 *
 * <p>잘못된 값({@code 0}/음수)은 {@code @Min(1)}으로 기동 시 fail-fast.
 */
@Validated
@ConfigurationProperties(prefix = "wallet.transfer.rate-limit")
public record TransferRateLimitProperties(
        @Min(1) Integer windowSeconds,
        @Min(1) Integer limit) {

    /** 운영 정책 확정 전 기본 윈도/임계값. */
    private static final int DEFAULT_WINDOW_SECONDS = 60;
    private static final int DEFAULT_LIMIT = 30;

    public TransferRateLimitProperties {
        if (windowSeconds == null) {
            windowSeconds = DEFAULT_WINDOW_SECONDS;
        }
        if (limit == null) {
            limit = DEFAULT_LIMIT;
        }
    }
}
