package com.gb.appadmin.domain.exchangeRatePolicy.entity;

import com.gb.appadmin.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 통화별 환율 정책(스프레드 설정).
 * 실제 환율에 spread를 더해 앱 환율로 제공한다.
 * 변경 이력은 {@link ExchangeRatePolicyHistory}에 기록된다.
 */
@Entity
@Getter
@Table(name = "exchange_rate_policies",
        indexes = {
                @Index(name = "idx_erp_currency_code", columnList = "currency_code"),
                @Index(name = "idx_erp_active", columnList = "active")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeRatePolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /** 통화 코드(KRW/USD/PHP 등) */
    @Column(name = "currency_code", length = 10, nullable = false, unique = true)
    private String currencyCode;

    /** 기준 환율 대비 스프레드(%) */
    @Column(name = "spread", precision = 18, scale = 4, nullable = false)
    private BigDecimal spread;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Builder
    private ExchangeRatePolicy(String publicId, String currencyCode, BigDecimal spread, Boolean active) {
        this.publicId = publicId;
        this.currencyCode = currencyCode;
        this.spread = spread;
        this.active = active != null ? active : true;
    }

    public void update(BigDecimal spread, Boolean active) {
        if (spread != null) this.spread = spread;
        if (active != null) this.active = active;
    }
}
