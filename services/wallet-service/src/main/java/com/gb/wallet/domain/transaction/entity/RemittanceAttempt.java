package com.gb.wallet.domain.transaction.entity;

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
 * REMITTANCE 외부 호출 시도 흔적. database.md remittance_attempts 표를 SSOT로 한다.
 *
 * <p><b>append-only (INSERT만).</b> 외부 Mock 은행 호출 직전 REQUIRES_NEW로 별도 커밋해 메인
 * 트랜잭션이 rollback돼도 흔적은 살아남는다. timeout-but-success 시 운영 reconcile 입력 자료다.
 *
 * <p>{@code transaction_audit_logs}에 박지 않은 이유: audit log는 transaction_id NOT NULL이라
 * 본 Transaction INSERT 전엔 행을 만들 수 없고, 또 "거래에 1:1로 묶이는 흔적" 의미를 흐린다.
 * 별도 테이블로 분리해 충전·1단계 송금엔 영향 없게 한다(CLAUDE.md §4, docs/database.md §3 부록).
 *
 * <p>{@link com.gb.wallet.global.common.entity.BaseEntity}는 상속하지 않는다 — append-only라
 * {@code updated_at}이 무의미하고 {@code created_at}은 {@code attempted_at}과 컬럼 의미가 동일하다.
 * 다른 테이블처럼 공통 컬럼을 두면 두 시각 컬럼(created_at·attempted_at)이 중복 저장되어 혼란.
 *
 * <p>{@code bank_account_id}는 Transaction 엔티티의 동일 컬럼 패턴(raw {@code Long})을 따른다 —
 * 시도 흔적 단계라 BankAccount 엔티티를 LAZY로 끌어올 일이 없다.
 */
@Entity
@Getter
@Table(
        name = "remittance_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_remittance_attempts_idempotency_key",
                columnNames = "idempotency_key"),
        indexes = @Index(name = "idx_remittance_attempts_user", columnList = "user_public_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RemittanceAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 멱등성 키 — 시도 1회당 1행. UNIQUE로 동시 race 흡수(같은 키 재시도는 흔적 1개로 유지). */
    @Column(name = "idempotency_key", length = 100, nullable = false)
    private String idempotencyKey;

    // member-service users.public_id 논리 참조. MSA 결합도 완화를 위해 물리 FK 없음.
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    /** 검증된 외부 계좌 id. Transaction.bankAccountId와 동일 raw Long 패턴. */
    @Column(name = "bank_account_id", nullable = false)
    private Long bankAccountId;

    /** 시도 차감액(amount + fee). 잔액 변화 흔적이 아니라 "이만큼 보내려고 시도함". */
    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", length = 10, nullable = false)
    private CurrencyType currencyCode;

    /** 외부 호출 직전 기록 시각. BaseEntity의 created_at과 분리해 시도 시점 의미를 명확히 표현. */
    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @Builder
    private RemittanceAttempt(String idempotencyKey, String userPublicId, Long bankAccountId,
                              BigDecimal amount, CurrencyType currencyCode, LocalDateTime attemptedAt) {
        this.idempotencyKey = idempotencyKey;
        this.userPublicId = userPublicId;
        this.bankAccountId = bankAccountId;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.attemptedAt = attemptedAt;
    }
}
