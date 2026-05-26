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

    @Builder
    private Wallet(String publicId, String userPublicId, WalletStatus status) {
        this.publicId = publicId;
        this.userPublicId = userPublicId;
        this.status = status != null ? status : WalletStatus.ACTIVE;
    }
}
