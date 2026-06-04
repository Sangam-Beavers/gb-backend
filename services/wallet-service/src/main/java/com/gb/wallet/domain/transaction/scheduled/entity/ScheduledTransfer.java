package com.gb.wallet.domain.transaction.scheduled.entity;

import com.gb.wallet.global.common.entity.BaseEntity;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.ScheduledTransferStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.TransferFrequency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정기 송금 설정. 매주/매월 자동 실행되는 송금의 메타 정보를 보관한다.
 *
 * <p>실행 자체는 별도 스케줄러(다음 사이클)가 {@code status=ACTIVE}이고 {@code next_run_date <= today}인
 * 행을 가져와 {@link com.gb.wallet.domain.transaction.service.TransferService#execute}를 호출한다. 실행 후
 * {@code last_run_at} 갱신 + {@code next_run_date} 재계산. 본 엔티티는 설정·조회·취소만 다루며 실제 송금
 * 거래는 {@code transactions} 테이블에 별도 INSERT된다.
 *
 * <p>INTERNAL_TRANSFER / REMITTANCE 둘 다 지원 — 송금 실행 API와 동일하게 {@code transferType}으로 분기.
 * INTERNAL은 {@code receiverPublicId}, REMITTANCE는 {@code bankAccountId}(내부 id, FK는 객체 매핑 안 함)를 사용.
 *
 * <p>{@code receiverName}은 설정 시점에 snapshot 저장(MemberClient 또는 BankAccount.holderName) — 송금 회차마다
 * 실행되는 transactions의 receiver_name으로 그대로 복사된다(외부 의존 없는 빠른 실행).
 */
@Entity
@Getter
@Table(
        name = "scheduled_transfers",
        indexes = {
                @Index(name = "idx_scheduled_transfers_user", columnList = "user_public_id"),
                @Index(name = "idx_scheduled_transfers_status_next",
                        columnList = "status, next_run_date")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduledTransfer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자(UUID). */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /** 송신자 회원 논리 참조 (member-service 경계, 물리 FK 없음). */
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    /** 송금 유형 — INTERNAL_TRANSFER / REMITTANCE 둘 중 하나. */
    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", length = 30, nullable = false)
    private TransactionType transferType;

    /** INTERNAL_TRANSFER일 때 수신자 user_public_id. REMITTANCE면 null. */
    @Column(name = "receiver_public_id", length = 36)
    private String receiverPublicId;

    /** REMITTANCE일 때 수신 은행 계좌의 내부 id. INTERNAL이면 null. (FK는 객체 매핑 안 함 — Transaction과 동일 raw Long 패턴) */
    @Column(name = "bank_account_id")
    private Long bankAccountId;

    /**
     * 수신자 본명 snapshot — 설정 시점에 박은 값. 실행 회차마다 transactions.receiver_name으로 복사된다.
     * INTERNAL은 MemberClient.getMember(receiver).name (fail-open → null 가능), REMITTANCE는
     * bankAccount.holderName (구 계좌면 null 가능).
     */
    @Column(name = "receiver_name", length = 100)
    private String receiverName;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", length = 10, nullable = false)
    private CurrencyType currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "receive_currency_code", length = 10, nullable = false)
    private CurrencyType receiveCurrencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", length = 20, nullable = false)
    private TransferFrequency frequency;

    /** 실행 기준일. WEEKLY는 1~7(ISO 요일), MONTHLY는 1~31. */
    @Column(name = "schedule_day", nullable = false)
    private int scheduleDay;

    /** 다음 실행 예정일(KST 기준 LocalDate). 실행 후 스케줄러가 갱신. */
    @Column(name = "next_run_date", nullable = false)
    private LocalDate nextRunDate;

    /** 마지막 실행 시각. 최초 실행 전이면 null. */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ScheduledTransferStatus status;

    @Column(name = "memo", length = 255)
    private String memo;

    @Builder
    private ScheduledTransfer(String publicId, String userPublicId, TransactionType transferType,
                              String receiverPublicId, Long bankAccountId, String receiverName,
                              BigDecimal amount, CurrencyType currencyCode, CurrencyType receiveCurrencyCode,
                              TransferFrequency frequency, int scheduleDay,
                              LocalDate nextRunDate, ScheduledTransferStatus status, String memo) {
        this.publicId = publicId;
        this.userPublicId = userPublicId;
        this.transferType = transferType;
        this.receiverPublicId = receiverPublicId;
        this.bankAccountId = bankAccountId;
        this.receiverName = receiverName;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.receiveCurrencyCode = receiveCurrencyCode;
        this.frequency = frequency;
        this.scheduleDay = scheduleDay;
        this.nextRunDate = nextRunDate;
        this.status = status;
        this.memo = memo;
    }

    /** 스케줄러가 1회차 실행을 완료한 뒤 호출 — last_run_at 기록 + next_run_date 갱신. 다음 사이클(스케줄러)에서 사용. */
    public void markExecuted(LocalDateTime executedAt, LocalDate nextRunDate) {
        this.lastRunAt = executedAt;
        this.nextRunDate = nextRunDate;
    }

    /** 일시정지 — 자동 실행 대상에서 제외. 사용자/시스템에 의한 일시 전환. */
    public void pause() {
        this.status = ScheduledTransferStatus.PAUSED;
    }

    /** 일시정지 해제 — 다시 자동 실행 대상으로. */
    public void resume() {
        this.status = ScheduledTransferStatus.ACTIVE;
    }

    /** 사용자 취소 — 영구 중단(소프트 삭제 의미). 재개 불가. */
    public void cancel() {
        this.status = ScheduledTransferStatus.CANCELLED;
    }
}
