package com.gb.appadmin.domain.feePolicy.entity;

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
 * 서비스별 수수료 정책.
 * service_type + currency 조합이 고유 키(논리적).
 * 변경 이력은 {@link FeePolicyHistory}에 기록된다.
 */
@Entity
@Getter
@Table(name = "fee_policies",
        indexes = {
                @Index(name = "idx_fee_policies_service_type", columnList = "service_type"),
                @Index(name = "idx_fee_policies_active", columnList = "active")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeePolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /** TRANSFER / EXCHANGE / CHARGE / CASHOUT */
    @Column(name = "service_type", length = 50, nullable = false)
    private String serviceType;

    /** FIXED / PERCENT */
    @Column(name = "fee_type", length = 20, nullable = false)
    private String feeType;

    /** FIXED: 고정 금액, PERCENT: 비율(0.0~100.0) */
    @Column(name = "fee_value", precision = 18, scale = 4, nullable = false)
    private BigDecimal feeValue;

    /** 최소 수수료(PERCENT 타입에서만 사용, nullable) */
    @Column(name = "min_fee", precision = 18, scale = 4)
    private BigDecimal minFee;

    /** 최대 수수료(PERCENT 타입에서만 사용, nullable) */
    @Column(name = "max_fee", precision = 18, scale = 4)
    private BigDecimal maxFee;

    /** 통화 코드(KRW/USD 등) */
    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Builder
    private FeePolicy(String publicId, String serviceType, String feeType, BigDecimal feeValue,
                      BigDecimal minFee, BigDecimal maxFee, String currency, Boolean active) {
        this.publicId = publicId;
        this.serviceType = serviceType;
        this.feeType = feeType;
        this.feeValue = feeValue;
        this.minFee = minFee;
        this.maxFee = maxFee;
        this.currency = currency;
        this.active = active != null ? active : true;
    }

    public void update(String feeType, BigDecimal feeValue, BigDecimal minFee, BigDecimal maxFee, Boolean active) {
        if (feeType != null) this.feeType = feeType;
        if (feeValue != null) this.feeValue = feeValue;
        if (minFee != null) this.minFee = minFee;
        if (maxFee != null) this.maxFee = maxFee;
        if (active != null) this.active = active;
    }
}
