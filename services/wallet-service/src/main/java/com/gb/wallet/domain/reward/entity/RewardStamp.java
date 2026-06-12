package com.gb.wallet.domain.reward.entity;

import com.gb.wallet.global.common.entity.BaseEntity;
import com.gb.wallet.global.common.enums.TransactionType;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 송금 적립 스탬프(append-only 원장). 송금 1건 COMPLETED당 1행이 쌓인다.
 *
 * <p><b>멱등 키 = {@code source_transaction_public_id}</b>(UNIQUE). 적립은 송금 커밋 후 이벤트로 비동기
 * 처리되는데, 이벤트가 중복 전달되거나 리스너가 재시도돼도 같은 거래로는 한 행만 들어간다(UNIQUE 위반은
 * 서비스에서 흡수). 금융 도메인 멱등성 원칙(CLAUDE.md §10).
 *
 * <p>회원({@code user_public_id})은 member-service 경계를 넘는 참조라 문자열만 보유하고 {@code @ManyToOne}
 * 매핑하지 않는다(CLAUDE.md §7). 사용자별 누적 개수 집계가 핵심 액세스 패턴이라 단일 컬럼 인덱스를 둔다.
 *
 * <p>{@code transfer_type}은 어떤 유형의 송금이 적립을 만들었는지 감사용으로만 보관한다(INTERNAL_TRANSFER /
 * REMITTANCE). 적립 정책 분기에는 쓰지 않는다(둘 다 1스탬프).
 */
@Entity
@Getter
@Table(
        name = "reward_stamps",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reward_stamps_source_tx", columnNames = "source_transaction_public_id"),
        indexes = @Index(name = "idx_reward_stamps_user", columnList = "user_public_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RewardStamp extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // member-service users.public_id 논리 참조. MSA 결합도 완화를 위해 물리 FK 없음.
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    /** 적립을 만든 송금 거래의 public_id(UUID). 멱등 키 — UNIQUE. */
    @Column(name = "source_transaction_public_id", length = 36, nullable = false)
    private String sourceTransactionPublicId;

    /** 적립을 만든 송금 유형(감사용). INTERNAL_TRANSFER / REMITTANCE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", length = 30, nullable = false)
    private TransactionType transferType;

    @Builder
    private RewardStamp(String userPublicId, String sourceTransactionPublicId, TransactionType transferType) {
        this.userPublicId = userPublicId;
        this.sourceTransactionPublicId = sourceTransactionPublicId;
        this.transferType = transferType;
    }
}
