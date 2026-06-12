package com.gb.wallet.domain.reward.entity;

import com.gb.wallet.global.common.entity.BaseEntity;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 적립 스탬프 N개(=목표 개수)를 채울 때마다 발급되는 쿠폰. 보상은 "송금 수수료 무료 1회"({@link CouponType}).
 *
 * <p><b>멱등 키 = {@code (user_public_id, cycle_no)}</b>(UNIQUE). {@code cycle_no}는 몇 번째 카드를 완성해
 * 발급된 쿠폰인지(=누적 스탬프 / 목표 개수)다. 적립 이벤트가 중복 처리돼도 같은 사이클로는 쿠폰이 한 장만
 * 발급된다(UNIQUE 위반은 서비스에서 흡수). 금융 도메인 멱등성 원칙(CLAUDE.md §10).
 *
 * <p>외부 노출 식별자는 {@code public_id}(UUID), 내부 {@code id}(BIGINT)는 응답/URL에 노출 금지(컨벤션 §5).
 * 회원은 {@code user_public_id} 문자열만 보유(MSA 경계, §7).
 *
 * <p>발급 시 상태는 {@link CouponStatus#ISSUED}. 사용({@code USED})·만료({@code EXPIRED}) 전이는 후속 이슈
 * (쿠폰 사용·만료 배치)에서 다룬다 — 본 엔티티는 발급까지만 책임진다.
 */
@Entity
@Getter
@Table(
        name = "coupons",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_coupons_public_id", columnNames = "public_id"),
                @UniqueConstraint(name = "uk_coupons_user_cycle", columnNames = {"user_public_id", "cycle_no"})
        },
        indexes = @Index(name = "idx_coupons_user", columnList = "user_public_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출용 식별자(UUID). */
    @Column(name = "public_id", length = 36, nullable = false)
    private String publicId;

    // member-service users.public_id 논리 참조. MSA 결합도 완화를 위해 물리 FK 없음.
    @Column(name = "user_public_id", length = 36, nullable = false)
    private String userPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private CouponType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private CouponStatus status;

    /** 몇 번째 카드 완성으로 발급됐는지(누적 스탬프 / 목표 개수). (user, cycle_no) UNIQUE의 일부. */
    @Column(name = "cycle_no", nullable = false)
    private int cycleNo;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Builder
    private Coupon(String publicId, String userPublicId, CouponType type, CouponStatus status,
                   int cycleNo, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.publicId = publicId;
        this.userPublicId = userPublicId;
        this.type = type;
        this.status = status;
        this.cycleNo = cycleNo;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }
}
