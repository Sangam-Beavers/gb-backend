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

    /**
     * 잔액을 {@code delta}만큼 증액한다(충전 등). {@code @Setter} 대신 의도가 드러나는 도메인 메서드로
     * 노출해 불변성 규칙(CLAUDE.md §4)을 지킨다. 영속 상태에서 호출하면 dirty checking으로 UPDATE된다.
     *
     * <p>{@code delta}가 null이거나 0 이하면 {@link IllegalArgumentException}을 던진다. 이는 외부 입력
     * 검증이 아니라 <em>내부 불변식 방어</em>다 — 호출 측(Service)이 이미 양수로 검증한 값을 받는다는 가정이며,
     * 위반 시 프로그래밍 오류이므로 비즈니스 예외(BusinessException) 대상이 아니다(CLAUDE.md §6 본문).
     */
    public void addBalance(BigDecimal delta) {
        if (delta == null || delta.signum() <= 0) {
            throw new IllegalArgumentException("증액 금액은 양수여야 합니다: " + delta);
        }
        this.balance = this.balance.add(delta);
    }
}
