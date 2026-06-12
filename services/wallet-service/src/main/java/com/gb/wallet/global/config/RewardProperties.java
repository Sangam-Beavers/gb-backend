package com.gb.wallet.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 송금 적립/쿠폰 정책 설정. {@code wallet.reward.*} 프로퍼티를 바인딩한다(#215).
 *
 * <p>{@code @ConfigurationPropertiesScan}(WalletServiceApplication, basePackages=com.gb.wallet)으로 자동
 * 등록되므로 별도 {@code @EnableConfigurationProperties}가 필요 없다({@link ChargeProperties}와 동일 사상).
 *
 * <p>미지정 시 compact 생성자에서 기본값을 채운다 — 운영 정책 확정/어드민 관리 이관 전까지의 데모 기본값.
 *
 * @param stampsPerCoupon 쿠폰 1장 발급에 필요한 스탬프 개수(스탬프 카드 한 장의 칸 수). 기본 5.
 * @param couponValidDays 발급 쿠폰의 유효기간(일). 기본 90.
 */
@ConfigurationProperties(prefix = "wallet.reward")
public record RewardProperties(Integer stampsPerCoupon, Integer couponValidDays) {

    private static final int DEFAULT_STAMPS_PER_COUPON = 5;
    private static final int DEFAULT_COUPON_VALID_DAYS = 90;

    public RewardProperties {
        if (stampsPerCoupon == null || stampsPerCoupon < 1) {
            stampsPerCoupon = DEFAULT_STAMPS_PER_COUPON;
        }
        if (couponValidDays == null || couponValidDays < 1) {
            couponValidDays = DEFAULT_COUPON_VALID_DAYS;
        }
    }
}
