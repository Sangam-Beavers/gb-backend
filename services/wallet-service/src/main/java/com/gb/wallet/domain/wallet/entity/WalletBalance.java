package com.gb.wallet.domain.wallet.entity;

import com.gb.wallet.global.common.entity.BaseEntity;
import com.gb.wallet.global.common.enums.CurrencyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지갑의 통화별 잔액. {@link Wallet}을 단방향(@ManyToOne LAZY)으로 참조한다.
 * 같은 wallet-service 내부 테이블 간 참조이므로 물리 FK(wallet_id)를 사용한다.
 * (wallet_id, currency_code)는 복합 UNIQUE.
 */
@Entity
@Getter
@Table(
        name = "wallet_balances",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_wallet_balances_wallet_currency",
                columnNames = {"wallet_id", "currency_code"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletBalance extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", length = 10, nullable = false)
    private CurrencyType currencyCode;

    @Column(name = "balance", precision = 18, scale = 4, nullable = false)
    private BigDecimal balance;

    @Builder
    private WalletBalance(Wallet wallet, CurrencyType currencyCode, BigDecimal balance) {
        this.wallet = wallet;
        this.currencyCode = currencyCode;
        this.balance = balance != null ? balance : BigDecimal.ZERO;
    }
}
