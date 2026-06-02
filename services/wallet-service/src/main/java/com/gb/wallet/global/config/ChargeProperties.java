package com.gb.wallet.global.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 충전 정책 설정. {@code wallet.charge.*} 프로퍼티를 바인딩한다.
 *
 * <p>단일 거래 한도를 외부화한다(명세 §12 ACCOUNT4007).
 *
 * <p>설정 미지정 시 기존 동작(1천만원)을 유지하도록 compact 생성자에서 기본값을 채운다.
 */
@ConfigurationProperties(prefix = "wallet.charge")
public record ChargeProperties(BigDecimal singleLimit) {

    /** 운영 정책 확정 전 기본 단일거래 한도. 기존 하드코딩 값과 동일(동작 불변). */
    private static final BigDecimal DEFAULT_SINGLE_LIMIT = new BigDecimal("10000000");

    public ChargeProperties {
        if (singleLimit == null) {
            singleLimit = DEFAULT_SINGLE_LIMIT;
        }
    }
}
