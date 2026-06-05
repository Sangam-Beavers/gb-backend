package com.gb.wallet.global.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 환전 정책 설정. {@code wallet.exchange.*} 프로퍼티를 바인딩한다.
 *
 * <p>환전 수수료율을 외부화한다(10D wallet-exchange-5 — 코드 상수 하드코딩 제거).
 * 수수료 = 신청 금액의 KRW 환산액 × {@code feeRate}, 소수 4자리 HALF_UP(계산은 ExchangeServiceImpl).
 *
 * <p>설정 미지정 시 기존 동작(0.5%)을 유지하도록 compact 생성자에서 기본값을 채운다
 * ({@link ChargeProperties}와 동일 사상). {@code @ConfigurationPropertiesScan}(WalletServiceApplication)으로
 * 자동 등록된다.
 */
@ConfigurationProperties(prefix = "wallet.exchange")
public record ExchangeProperties(BigDecimal feeRate) {

    /** 운영 정책 확정 전 기본 수수료율. 기존 하드코딩 값(0.5%)과 동일(동작 불변). */
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.005");

    public ExchangeProperties {
        if (feeRate == null) {
            feeRate = DEFAULT_FEE_RATE;
        }
    }
}
