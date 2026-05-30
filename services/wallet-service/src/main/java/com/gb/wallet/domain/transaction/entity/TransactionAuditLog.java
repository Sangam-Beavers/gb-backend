package com.gb.wallet.domain.transaction.entity;

import com.gb.wallet.global.common.entity.BaseEntity;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
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
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거래 감사 로그 + 상태 이력. database.md transaction_audit_logs 표를 SSOT로 한다.
 *
 * <p><b>append-only (INSERT만).</b> 한번 기록된 로그는 변경하지 않으므로 update 메서드/{@code @Setter}를
 * 두지 않는다(CLAUDE.md §4, database.md §8 체크리스트). 충전 처리는 {@code @Transactional} 안에서
 * [잔액 조회 → 검증 → 증액 → 이 로그 INSERT → 커밋] 순서로 쌓인다.
 *
 * <p>{@code transaction}은 같은 wallet-service 스키마 내부 참조이므로 BIGINT FK + 단방향
 * {@code @ManyToOne}(LAZY)로 매핑한다(CLAUDE.md §7 "내부는 BIGINT FK"). 반면 회원
 * ({@code user_public_id})은 member-service 경계를 넘는 참조라 문자열만 보유하고 {@code @ManyToOne}
 * 매핑하지 않는다.
 *
 * <p>{@code currencyCode}는 거래 당시 통화를 그대로 보존한다(FK 없이 직접 저장 — 사후 마스터 변경에
 * 영향받지 않도록). {@link BaseEntity}를 상속해 {@code created_at}/{@code updated_at}을 공통 제공받는다
 * (INSERT-only라 {@code updated_at}은 실질적으로 변하지 않지만, 모든 테이블 공통 컬럼 규약 — database.md §6 —
 * 과 다른 엔티티와의 일관성을 위해 컬럼은 그대로 둔다).
 */
@Entity
@Getter
@Table(name = "transaction_audit_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionAuditLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 대상 거래. transactions.id FK(스키마 내부 참조). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    // member-service users.public_id 논리 참조. MSA 결합도 완화를 위해 물리 FK 없음.
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    /** 행위 구분(CHARGE/TRANSFER/REMITTANCE/EXCHANGE/CANCEL 등). 본 PR에서는 "CHARGE". */
    @Column(name = "action", length = 50, nullable = false)
    private String action;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", length = 10, nullable = false)
    private CurrencyType currencyCode;

    /** 거래 전 잔액. */
    @Column(name = "before_balance", precision = 18, scale = 4, nullable = false)
    private BigDecimal beforeBalance;

    /** 거래 후 잔액. 멱등성 재요청 시 첫 응답의 wallet_balance를 재현하는 SSOT. */
    @Column(name = "after_balance", precision = 18, scale = 4, nullable = false)
    private BigDecimal afterBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TransactionStatus status;

    /** 실패 시 사유 메시지. 성공 로그는 null. */
    @Column(name = "reason", length = 255)
    private String reason;

    /** 요청 IP(IPv6 포함). 미상이면 null. */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Builder
    private TransactionAuditLog(Transaction transaction, String userPublicId, String action,
                               BigDecimal amount, CurrencyType currencyCode,
                               BigDecimal beforeBalance, BigDecimal afterBalance,
                               TransactionStatus status, String reason, String ipAddress) {
        this.transaction = transaction;
        this.userPublicId = userPublicId;
        this.action = action;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.beforeBalance = beforeBalance;
        this.afterBalance = afterBalance;
        this.status = status;
        this.reason = reason;
        this.ipAddress = ipAddress;
    }
}