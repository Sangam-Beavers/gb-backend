package com.gb.member.domain.member.entity;

import com.gb.member.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;            // 내부 식별자(BIGINT). 경계 밖 노출 금지

    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String publicId;    // 대외 식별자(UUID). 응답·URL에는 이 값만 노출

    // 방식 B(OIDC): 외부 IdP(개발=Authentik / 운영=Cognito)가 발급한 JWT의 sub를 저장해
    // "토큰의 sub → 우리 회원"을 매핑한다.
    // TODO: ROPC 로그인 연동 시 가입 단계에서 IdP sub를 받아 채우고 NOT NULL/저장 시점을 확정한다.
    //       (database.md §2 users.auth_provider_id = UNIQUE NOT NULL)
    @Column(unique = true)
    private String authProviderId;

    @Column(nullable = false, unique = true)
    private String email;       // 로그인 아이디

    // 방식 B: 비밀번호는 IdP가 보유·검증한다(우리 DB 저장 안 함). password 컬럼은 두지 않는다.

    @Column(nullable = false)
    private String name;        // 이름

    @Column(nullable = false, unique = true)
    private String nickname;    // 닉네임 (중복 안 됨)

    @Column(nullable = false)
    private String nationality; // 국적

    @Column(nullable = false)
    private String language;    // 주 사용 언어

    // 약관 동의 증적(컴플라이언스). 가입 시 필수 동의를 받았다는 사실과 시각을 보관한다(명세 auth §2).
    // 동의는 가입 단계에서 @AssertTrue로 강제되므로 저장 값은 항상 true이나, "언제 동의했는지"도 함께 남긴다.
    // @ColumnDefault: ddl-auto:update가 기존 행이 있는 members 테이블에 NOT NULL 컬럼을 ADD할 때
    //   DEFAULT가 없으면(특히 DATETIME) MySQL strict 모드에서 실패하므로 DB 기본값을 명시해 기존 행을 백필한다.
    //   (신규 가입은 항상 빌더로 명시값을 채우므로 기본값은 마이그레이션 백필 용도다.)
    @Column(name = "terms_agreed", nullable = false)
    @ColumnDefault("false")
    private boolean termsAgreed;

    @Column(name = "privacy_agreed", nullable = false)
    @ColumnDefault("false")
    private boolean privacyAgreed;

    @Column(name = "consent_agreed_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime consentAgreedAt;

    // 신분증 인증 배지 여부. user_verifications가 APPROVED 되면 true로 반영(database.md §members).
    // 기본 false(가입 직후 미인증). @Builder에는 포함하지 않아 inline 기본값이 유지된다.
    @Column(name = "is_verified", nullable = false)
    private boolean isVerified = false;

    // 자기소개(한 줄 소개). 마이페이지 프로필 수정 화면에서 입력. 선택값(미입력 시 null).
    @Column(name = "bio", length = 200)
    private String bio;

    // 탈퇴(soft delete) 시각. null=활성, 값이 있으면 탈퇴한 회원.
    // createdAt/updatedAt은 BaseEntity(JPA Auditing)가 채운다(가입 시각 = createdAt).
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    public void prePersist() {
        // publicId는 보통 Service에서 미리 생성해 IdP(attributes.public_id)와 동일 값으로 넘긴다.
        // 여기서는 빌더로 받지 않은 경로(테스트 등)를 위한 fallback으로만 생성한다.
        // createdAt 세팅은 Auditing(@CreatedDate)으로 이관됐다.
        if (this.publicId == null) {
            this.publicId = UUID.randomUUID().toString();
        }
    }

    @Builder
    public Member(String publicId, String email, String name,
                  String nickname, String nationality, String language,
                  String authProviderId,
                  boolean termsAgreed, boolean privacyAgreed, LocalDateTime consentAgreedAt) {
        this.publicId = publicId;
        this.email = email;
        this.name = name;
        this.nickname = nickname;
        this.nationality = nationality;
        this.language = language;
        this.authProviderId = authProviderId;
        this.termsAgreed = termsAgreed;
        this.privacyAgreed = privacyAgreed;
        this.consentAgreedAt = consentAgreedAt;
    }

    /**
     * IdP 프로비저닝 후 부여받은 식별자(sub)를 1회 채운다(MEM-02). 이메일 가입은 "로컬 row 선점(save) →
     * IdP provision → 그 결과(sub) 채우기" 순서로 처리해, IdP 호출 전에 이메일/닉네임 UNIQUE 경합을 먼저
     * 걸러 <b>IdP 고아계정</b>을 막는다(provision 실패 시 트랜잭션 롤백으로 로컬 row도 사라짐). authProviderId가
     * provision 후에야 정해지므로 save 시점엔 비어 있고(현재 컬럼 nullable — 엔티티 상단 NOT NULL TODO 참조),
     * 같은 트랜잭션 안에서 이 메서드로 채운 뒤 커밋한다(커밋된 상태는 항상 non-null).
     */
    public void assignAuthProviderId(String authProviderId) {
        this.authProviderId = authProviderId;
    }

    /** 주 사용 언어 변경. updatedAt은 Auditing(dirty checking)으로 자동 갱신된다. */
    public void changeLanguage(String language) {
        this.language = language;
    }

    /**
     * 마이페이지 프로필 수정: 닉네임·언어·자기소개를 한 번에 변경한다.
     * (국적·이미지·이메일은 이 화면에서 바꾸지 않는다 — 이미지는 별도 API, 그 외는 불변.)
     */
    public void updateProfile(String nickname, String language, String bio) {
        this.nickname = nickname;
        this.language = language;
        this.bio = bio;
    }

    /** 신분증 인증 승인 시 인증 배지를 부여한다. (verification 도메인 서비스에서 호출) */
    public void markVerified() {
        this.isVerified = true;
    }

    /** 탈퇴(soft delete): deleted_at만 세팅하고 실제 row는 보존한다. (community softDelete 패턴) */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}