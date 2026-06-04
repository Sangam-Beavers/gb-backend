package com.gb.wallet.domain.account.entity;

import com.gb.wallet.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 등록한 외부 은행 계좌(충전·현금화용). 같은 wallet-service 스키마 내부 참조라
 * {@link Bank}는 물리 FK(bank_id)로 매핑한다(CLAUDE.md §7: "내부는 BIGINT FK").
 *
 * <p>회원({@code user_public_id})은 member-service 경계를 넘는 참조라 문자열만 보유하고
 * {@code @ManyToOne} 매핑하지 않는다. 사용자별 목록 조회가 핵심 액세스 패턴이라
 * {@code user_public_id} 단일 컬럼 인덱스를 둔다.
 *
 * <p>{@code account_number}는 평문 저장이며 응답 직전 마스킹만 적용된다.
 */
@Entity
@Getter
@Table(
        name = "bank_accounts",
        indexes = @Index(name = "idx_bank_accounts_user", columnList = "user_public_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자(UUID). */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    // member-service users.public_id 논리 참조. MSA 결합도 완화를 위해 물리 FK 없음.
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_id", nullable = false)
    private Bank bank;

    /**
     * 계좌번호. 응답 시 {@code AccountResponse}에서 마스킹된다(평문은 클라이언트에 노출 안 됨).
     *
     * <p>TODO(D-3, wallet-account-charge-3): 현재 데모 평문 저장 — 공용 AES-256 암복호 유틸(D-3) 도입 후
     * 암호화 컬럼으로 교체한다(신분증 {@code document_number}와 동일 계열, 함께 sequencing). 단 WACC-06
     * 부분 유니크 생성 컬럼과 dedup 조회가 account_number 평문 동등성에 의존하므로, 암호화 시 결정적 암호
     * 또는 별도 HMAC blind-index 컬럼이 필요하다(랜덤 IV AES면 동등성 매칭이 깨짐).
     */
    @Column(name = "account_number", length = 100, nullable = false)
    private String accountNumber;

    /**
     * 외부 계좌 예금주명. 계좌 등록 시 holder 조회({@code GET /accounts/holder}) 응답에서 받아 저장한다
     * (외부 은행이 알려준 진짜 예금주) — 사용자 입력값이 아닌 외부 신뢰 source. REMITTANCE 송금 시
     * {@code Transaction.receiverName}에 snapshot으로 복사돼 송금 확인증의 receiver_name 출처가 된다.
     *
     * <p>NULLABLE — 컬럼 추가 전 등록된 기존 계좌는 null. 신규 등록은 Service에서 항상 채움.
     */
    @Column(name = "holder_name", length = 100)
    private String holderName;

    /** 외부(Mock) 은행이 발급한 계좌 토큰. 인증 완료 전이면 null. */
    @Column(name = "mock_account_token", length = 36)
    private String mockAccountToken;

    /** 가상계좌(앱 내부 발급) 여부. */
    @Column(name = "is_virtual", nullable = false)
    private boolean isVirtual;

    /** 주 계좌 여부(사용자당 1개). */
    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    /** 활성화 여부. 비활성(soft-delete) 계좌는 목록 조회에서 제외된다. */
    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Builder
    private BankAccount(String publicId, String userPublicId, Bank bank, String accountNumber,
                        String holderName, String mockAccountToken,
                        boolean isVirtual, boolean isPrimary, boolean isActive) {
        this.publicId = publicId;
        this.userPublicId = userPublicId;
        this.bank = bank;
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.mockAccountToken = mockAccountToken;
        this.isVirtual = isVirtual;
        this.isPrimary = isPrimary;
        this.isActive = isActive;
    }

    /** 주 계좌로 지정한다(주 계좌 변경 시 사용). {@code @Setter} 대신 의도를 드러내는 도메인 메서드. */
    public void markAsPrimary() {
        this.isPrimary = true;
    }

    /** 주 계좌 지정을 해제한다(다른 계좌를 주 계좌로 바꿀 때 기존 주 계좌에 적용). */
    public void releasePrimary() {
        this.isPrimary = false;
    }

    /** soft-delete — 비활성 처리한다. 비활성 계좌는 목록·조회 finder에서 제외된다. */
    public void deactivate() {
        this.isActive = false;
    }
}
