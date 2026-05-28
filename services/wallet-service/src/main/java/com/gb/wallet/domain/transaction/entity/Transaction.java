package com.gb.wallet.domain.transaction.entity;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.global.common.entity.BaseEntity;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
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
 * 모든 금융 거래 마스터. {@code type}으로 유형(CHARGE/INTERNAL_TRANSFER/REMITTANCE/EXCHANGE) 구분,
 * 유형별로 사용 컬럼이 다르다. database.md transactions 표를 SSOT로 한다.
 *
 * <p>같은 wallet-service 스키마 내부 참조는 BIGINT FK + 단방향 {@code @ManyToOne}을 사용한다
 * ({@link Wallet} 참조). {@code bankAccountId}는 BankAccount 엔티티가 아직 없으므로 임시로
 * 원시 Long 컬럼으로 둔다(아래 TODO 참고).
 */
@Entity
@Getter
@Table(name = "transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 거래 번호(UUID). */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /** 출금 주머니. wallets.id FK. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private TransactionType type;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", length = 10, nullable = false)
    private CurrencyType currencyCode;

    @Column(name = "fee", precision = 18, scale = 4, nullable = false)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TransactionStatus status;

    /** 멱등성 키. UNIQUE + Redis 분산 락으로 중복 거래 방지. */
    @Column(name = "idempotency_key", length = 100, nullable = false, unique = true)
    private String idempotencyKey;

    /** INTERNAL_TRANSFER 수취 주머니. wallets.id FK, nullable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_wallet_id")
    private Wallet receiverWallet;

    // TODO: BankAccount 엔티티 추가 후 @ManyToOne(fetch = LAZY) BankAccount + @JoinColumn(name = "bank_account_id")로 교체.
    //       현재는 BankAccount 엔티티 미작성으로 원시 Long FK 컬럼으로만 매핑한다.
    /** REMITTANCE 수취 계좌. bank_accounts.id FK, nullable. */
    @Column(name = "bank_account_id")
    private Long bankAccountId;

    /** REMITTANCE 수취인명. */
    @Column(name = "receiver_name", length = 100)
    private String receiverName;

    /** 수취 금액(환율 적용 후). */
    @Column(name = "receive_amount", precision = 18, scale = 4)
    private BigDecimal receiveAmount;

    /** 수취/환전 통화. */
    @Enumerated(EnumType.STRING)
    @Column(name = "receive_currency_code", length = 10)
    private CurrencyType receiveCurrencyCode;

    /** 적용 환율. */
    @Column(name = "exchange_rate", precision = 18, scale = 8)
    private BigDecimal exchangeRate;

    /** EXCHANGE 환전 후 금액. */
    @Column(name = "to_amount", precision = 18, scale = 4)
    private BigDecimal toAmount;

    @Column(name = "memo", length = 255)
    private String memo;

    @Builder
    private Transaction(
            String publicId,
            Wallet wallet,
            TransactionType type,
            BigDecimal amount,
            CurrencyType currencyCode,
            BigDecimal fee,
            TransactionStatus status,
            String idempotencyKey,
            Wallet receiverWallet,
            Long bankAccountId,
            String receiverName,
            BigDecimal receiveAmount,
            CurrencyType receiveCurrencyCode,
            BigDecimal exchangeRate,
            BigDecimal toAmount,
            String memo) {
        this.publicId = publicId;
        this.wallet = wallet;
        this.type = type;
        this.amount = amount;
        this.currencyCode = currencyCode;
        // DB DEFAULT 0과 일치: 빌더에서 누락 시 0으로 채운다.
        this.fee = fee != null ? fee : BigDecimal.ZERO;
        // DB DEFAULT 'PENDING'과 일치.
        this.status = status != null ? status : TransactionStatus.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.receiverWallet = receiverWallet;
        this.bankAccountId = bankAccountId;
        this.receiverName = receiverName;
        this.receiveAmount = receiveAmount;
        this.receiveCurrencyCode = receiveCurrencyCode;
        this.exchangeRate = exchangeRate;
        this.toAmount = toAmount;
        this.memo = memo;
    }
}
