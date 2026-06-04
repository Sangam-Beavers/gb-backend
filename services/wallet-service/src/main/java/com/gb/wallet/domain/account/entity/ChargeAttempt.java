package com.gb.wallet.domain.account.entity;

import com.gb.wallet.global.common.enums.CurrencyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CHARGE(충전) 외부 호출 시도 흔적. database.md charge_attempts 표를 SSOT로 한다.
 *
 * <p><b>왜 remittance_attempts와 분리하나(WACC-01):</b> 충전은 외부계좌 {@code withdrawal}(차감), 송금은
 * {@code payout}(증액)으로 외부 계좌에 대한 <b>돈 방향이 정반대</b>다. 고아 흔적(외부 성공인데 메인 tx 롤백)이
 * 생겼을 때 reconcile 교정 방향도 정반대(충전=환불, 송금=클로백)이므로, 유형을 섞지 않고 별도 테이블로 둔다.
 * 구조는 {@link com.gb.wallet.domain.transaction.entity.RemittanceAttempt}와 동일하다(append-only,
 * REQUIRES_NEW로 외부 호출 직전 별도 커밋 — 메인 rollback돼도 흔적 보존, timeout-but-success reconcile 입력).
 *
 * <p>{@code transaction_audit_logs}에 박지 않은 이유는 remittance_attempts와 동일하다(audit log는
 * {@code transaction_id} NOT NULL이라 본 Transaction INSERT 전엔 행 생성 불가). {@code BaseEntity}는
 * 상속하지 않는다 — append-only라 {@code updated_at}이 무의미하고 {@code created_at}은 {@code attempted_at}과
 * 의미가 같다. {@code bank_account_id}는 Transaction과 동일 raw {@code Long} 패턴.
 */
@Entity
@Getter
@Table(
        name = "charge_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_charge_attempts_idempotency_key",
                columnNames = "idempotency_key"),
        indexes = @Index(name = "idx_charge_attempts_user", columnList = "user_public_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChargeAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 멱등성 키 — 시도 1회당 1행. UNIQUE로 동시 race 흡수(같은 키 재시도는 흔적 1개로 유지). */
    @Column(name = "idempotency_key", length = 100, nullable = false)
    private String idempotencyKey;

    // member-service users.public_id 논리 참조. MSA 결합도 완화를 위해 물리 FK 없음.
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    /** 출금(차감)을 시도한 외부 계좌 id. Transaction.bankAccountId와 동일 raw Long 패턴. */
    @Column(name = "bank_account_id", nullable = false)
    private Long bankAccountId;

    /** 시도 차감액(외부 계좌에서 빼려는 충전 금액). 잔액 변화가 아니라 "이만큼 출금 시도함". */
    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", length = 10, nullable = false)
    private CurrencyType currencyCode;

    /** 외부 호출 직전 기록 시각. */
    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @Builder
    private ChargeAttempt(String idempotencyKey, String userPublicId, Long bankAccountId,
                          BigDecimal amount, CurrencyType currencyCode, LocalDateTime attemptedAt) {
        this.idempotencyKey = idempotencyKey;
        this.userPublicId = userPublicId;
        this.bankAccountId = bankAccountId;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.attemptedAt = attemptedAt;
    }
}