package com.gb.wallet.domain.wallet.entity;

import com.gb.wallet.global.common.entity.BaseEntity;
import com.gb.wallet.global.common.enums.WalletStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자당 1개의 지갑. 통화별 잔액은 {@link WalletBalance}가 별도 보유한다(단방향).
 */
@Entity
@Getter
@Table(name = "wallets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자(UUID). */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    // member-service users.public_id 논리 참조. MSA 결합도 완화를 위해 물리 FK 없음.
    @Column(name = "user_public_id", length = 36, nullable = false, unique = true)
    private String userPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WalletStatus status;

    /**
     * 송금 PIN(6자리)의 BCrypt 해시. 설정 전엔 null(=PIN 미설정).
     * 평문 PIN은 저장하지 않는다 — 검증은 {@code PasswordEncoder.matches}로 한다.
     */
    @Column(name = "transfer_pin_hash", length = 72)
    private String transferPinHash;

    @Builder
    private Wallet(String publicId, String userPublicId, WalletStatus status) {
        this.publicId = publicId;
        this.userPublicId = userPublicId;
        this.status = status != null ? status : WalletStatus.ACTIVE;
    }

    /**
     * 신규 지갑을 만든다. 신분증 인증(이슈 #152)이 통과되면 이 팩토리로 사용자당 1개의 지갑이 개설된다.
     * {@code publicId}는 UUID로 자동 생성, 상태는 ACTIVE로 시작한다. 사용자당 1개 보장은 DB
     * {@code wallets.user_public_id UNIQUE} 제약 + Service의 멱등 처리(존재하면 그대로 반환)로 한다.
     */
    public static Wallet create(String userPublicId) {
        return Wallet.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .status(WalletStatus.ACTIVE)
                .build();
    }

    /** 송금 PIN이 설정돼 있는지. */
    public boolean hasTransferPin() {
        return transferPinHash != null;
    }

    /** 송금 PIN 해시를 설정/변경한다(@Setter 대신 의도를 드러내는 도메인 메서드). */
    public void changeTransferPin(String transferPinHash) {
        this.transferPinHash = transferPinHash;
    }
}
