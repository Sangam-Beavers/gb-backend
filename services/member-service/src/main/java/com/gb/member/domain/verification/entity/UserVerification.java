package com.gb.member.domain.verification.entity;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.global.common.entity.BaseEntity;
import com.gb.member.global.security.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 신분증 인증 요청/결과. (database.md §user_verifications SSOT)
 *
 * <p>member 내부 테이블이므로 {@code user_id}는 BIGINT FK(@ManyToOne LAZY 단방향)로 매핑한다(CLAUDE §4·§7).
 * 인증이 APPROVED가 되면 {@code members.is_verified = TRUE}로 반영하는데, 그 반영은 서비스가
 * {@link Member#markVerified()}로 수행한다(엔티티 간 직접 결합 대신 서비스 오케스트레이션).
 *
 * <p>{@code documentNumber}는 명세에 따라 <b>AES-256-GCM 암호화 저장</b>한다 —
 * {@link EncryptedStringConverter}가 영속/조회 시점에 자동 변환하므로 서비스/리포지터리 코드에서는
 * 평문처럼 다루면 된다. 컬럼 길이는 평문 100자 기준 GCM(IV 12B + tag 16B) + Base64 오버헤드를
 * 흡수할 수 있도록 255로 잡았다.
 */
@Entity
@Table(name = "user_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserVerification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 인증 신청 회원(member 내부 참조 → BIGINT FK). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 30, nullable = false)
    private IdentityDocumentType documentType;

    /**
     * 문서 번호. DB에는 {@link EncryptedStringConverter}를 통해 AES-256-GCM ciphertext(Base64)로 저장되고,
     * 엔티티 필드에는 평문으로 노출된다. 컬럼 길이 255는 평문 100자 + GCM(28B) + Base64 오버헤드 흡수치.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "document_number", length = 255, nullable = false)
    private String documentNumber;

    @Column(name = "s3_key", length = 500, nullable = false)
    private String s3Key;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private VerificationStatus status;

    /** 관리자(또는 자동) 검토 시각. 미검토 시 null. */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Builder
    private UserVerification(Member member, IdentityDocumentType documentType,
                             String documentNumber, String s3Key,
                             VerificationStatus status, LocalDateTime reviewedAt) {
        this.member = member;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.s3Key = s3Key;
        this.status = status != null ? status : VerificationStatus.PENDING;
        this.reviewedAt = reviewedAt;
    }

    /**
     * 형식 검증 통과 시 즉시 승인된 인증 레코드를 만든다(데모 흐름).
     * status=APPROVED, reviewed_at=now. 실 운영에서 검토 단계를 붙이면 PENDING 빌더로 교체한다.
     */
    public static UserVerification approved(Member member, IdentityDocumentType documentType,
                                            String documentNumber, String s3Key) {
        return UserVerification.builder()
                .member(member)
                .documentType(documentType)
                .documentNumber(documentNumber)
                .s3Key(s3Key)
                .status(VerificationStatus.APPROVED)
                .reviewedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
}
