package com.gb.member.domain.member.entity;

import com.gb.member.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Builder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    // "토큰의 sub → 우리 회원"을 매핑한다. 가입이 IdP-first 단일 INSERT(11D member-idp-1·core-2,
    // MemberServiceImpl.signup 참조)라 INSERT 시점에 항상 값을 가지므로 NOT NULL을 확정한다
    // (database.md §members = UNIQUE NOT NULL 일치). 기존 dev 컬럼의 물리 NULL 허용은 ddl-auto:update가
    // 조이지 않으므로 신규 생성 환경부터 반영된다(커밋된 행은 종전 설계에서도 전부 non-null).
    @Column(unique = true, nullable = false)
    private String authProviderId;

    // 컬럼 길이 = database.md §members SSOT (11D member-core-1). 입력 상한은 DTO @Size가 같은 값으로
    //   먼저 막는다(초과 입력이 INSERT 단계 500으로 떨어지지 않게). dev의 기존 VARCHAR(255) 컬럼은
    //   ddl-auto:update가 축소하지 않으므로 신규 생성 환경부터 길이가 반영된다(기능 영향 없음).
    @Column(nullable = false, unique = true, length = 255)
    private String email;       // 로그인 아이디

    // 방식 B: 비밀번호는 IdP가 보유·검증한다(우리 DB 저장 안 함). password 컬럼은 두지 않는다.

    @Column(nullable = false, length = 100)
    private String name;        // 이름

    @Column(nullable = false, unique = true, length = 50)
    private String nickname;    // 닉네임 (중복 안 됨)

    @Column(nullable = false, length = 10)
    private String nationality; // 국적 (ISO 3166-1 alpha-2)

    @Column(nullable = false, length = 10)
    private String language;    // 주 사용 언어 (BCP 47 소문자)

    // 성별·연령대 (이슈 #203). 가입 시 수집하는 필수 값. enum STRING 매핑(database.md §members SSOT).
    // @ColumnDefault: ddl-auto:update가 기존 행이 있는 members에 NOT NULL 컬럼을 ADD할 때 기존 행을
    //   백필한다(termsAgreed와 동일 사유). 성별·연령대는 자연 기본값이 없어 기본값은 "기존 행 마이그레이션
    //   전용"이며, 신규 가입은 항상 빌더로 사용자 입력 실제 값을 명시 저장한다(아래 @Builder 인자).
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 10)
    @ColumnDefault("'MALE'")
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_range", nullable = false, length = 20)
    @ColumnDefault("'TWENTIES'")
    private AgeRange ageRange;

    // 약관 동의 증적(컴플라이언스). 가입 시 필수 동의를 받았다는 사실과 시각을 보관한다(명세 auth §2).
    // 동의는 가입 단계에서 @AssertTrue로 강제되므로 저장 값은 항상 true이나, "언제 동의했는지"도 함께 남긴다.
    // @ColumnDefault: ddl-auto:update가 기존 행이 있는 members 테이블에 NOT NULL 컬럼을 ADD할 때
    //   DEFAULT가 없으면(특히 DATETIME) MySQL strict 모드에서 실패하므로 DB 기본값을 명시해 기존 행을 백필한다.
    //   동의 컬럼의 기본값은 "동의(true)"로 둔다(신규 가입은 항상 빌더로 true를 명시하므로 기본값은
    //   기존 행 마이그레이션 백필 전용 — 기존 회원을 동의 상태로 본다).
    @Column(name = "terms_agreed", nullable = false)
    @ColumnDefault("true")
    private boolean termsAgreed;

    @Column(name = "privacy_agreed", nullable = false)
    @ColumnDefault("true")
    private boolean privacyAgreed;

    // 정밀도 주의: LocalDateTime은 MySQL datetime(6)로 매핑되므로 DEFAULT도 CURRENT_TIMESTAMP(6)이어야 한다.
    //   정밀도 없는 CURRENT_TIMESTAMP를 쓰면 datetime(6)에 대해 MySQL이 error 1067로 거부하고,
    //   ddl-auto:update가 그 ALTER를 조용히 삼켜 컬럼이 안 생긴다(신규 MySQL 배포 시 가입 INSERT 실패).
    @Column(name = "consent_agreed_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    private LocalDateTime consentAgreedAt;

    // 신분증 인증 배지 여부. user_verifications가 APPROVED 되면 true로 반영(database.md §members).
    // 기본 false(가입 직후 미인증). @Builder에는 포함하지 않아 inline 기본값이 유지된다.
    @Column(name = "is_verified", nullable = false)
    private boolean isVerified = false;

    // 마일스톤 기반 신뢰등급(이슈 #193 — 레거시 "생활온도" 대체). Phase 1은 NEWCOMER/VERIFIED 2단계.
    // 기본 NEWCOMER(가입 직후). @Builder에는 포함하지 않아 inline 기본값이 유지된다(isVerified와 동일 패턴).
    // 산정 규칙은 TrustGradeService.recalculate 단일 진입점(Phase 2 Kafka Consumer도 같은 메서드 호출).
    // @ColumnDefault: ddl-auto:update가 기존 행이 있는 members에 NOT NULL 컬럼을 ADD할 때 기존 행을
    //   'NEWCOMER'로 백필한다(termsAgreed와 동일 사유). 단, 기존 is_verified=true 회원의 VERIFIED 승격은
    //   DDL 기본값으로 불가 — 별도 백필 SQL(UPDATE members SET trust_grade='VERIFIED' WHERE is_verified=1)을
    //   사람이 확인 후 1회 수행한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "trust_grade", nullable = false, length = 20)
    @ColumnDefault("'NEWCOMER'")
    private TrustGrade trustGrade = TrustGrade.NEWCOMER;

    // 자기소개(한 줄 소개). 마이페이지 프로필 수정 화면에서 입력. 선택값(미입력 시 null).
    @Column(name = "bio", length = 200)
    private String bio;

    // 프로필 아바타 색조(hue) 회전 각도(0~359). 마이페이지 "색깔 변경"에서 무작위로 고른 값을 저장해
    // 사진/Identicon에 hue-rotate로 적용한다(이미지 자체는 안 바꾸고 표시 색만 회전). 기본 0(회전 없음).
    // @ColumnDefault: ddl-auto:update가 기존 행이 있는 members에 NOT NULL 컬럼을 ADD할 때 0으로 백필한다
    //   (isAdmin/communityBanned와 동일 사유). @Builder엔 없어 신규 가입은 inline 기본값(0)이 유지된다.
    @Column(name = "avatar_hue", nullable = false)
    @ColumnDefault("0")
    private int avatarHue = 0;

    // 관리자가 설정하는 계정 상태. 기본값 ACTIVE. ddl-auto:update가 신규 컬럼을 ADD하므로
    // 기존 행은 DEFAULT 값('ACTIVE')으로 채워진다.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @ColumnDefault("'ACTIVE'")
    private MemberStatus status = MemberStatus.ACTIVE;

    // 어드민 계정 여부. true면 관리자 회원 목록에서 제외된다.
    // ddl-auto:update가 신규 컬럼을 ADD하므로 기존 행은 DEFAULT(false)로 채워진다.
    @Column(name = "is_admin", nullable = false)
    @ColumnDefault("false")
    private boolean isAdmin = false;

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
                  Gender gender, AgeRange ageRange,
                  String authProviderId,
                  boolean termsAgreed, boolean privacyAgreed, LocalDateTime consentAgreedAt) {
        this.publicId = publicId;
        this.email = email;
        this.name = name;
        this.nickname = nickname;
        this.nationality = nationality;
        this.language = language;
        this.gender = gender;
        this.ageRange = ageRange;
        this.authProviderId = authProviderId;
        this.termsAgreed = termsAgreed;
        this.privacyAgreed = privacyAgreed;
        this.consentAgreedAt = consentAgreedAt;
    }

    // (제거됨) assignAuthProviderId — 과거 "로컬 선점 → IdP → sub UPDATE"(MEM-02) 설계의 잔재.
    //   가입이 IdP-first 단일 INSERT로 재설계되면서(11D member-idp-1·core-2) sub는 빌더로만 채워진다.

    /** 주 사용 언어 변경. updatedAt은 Auditing(dirty checking)으로 자동 갱신된다. */
    public void changeLanguage(String language) {
        this.language = language;
    }

    /**
     * 마이페이지 프로필 수정: 닉네임·언어·자기소개·아바타 색상을 한 번에 변경한다.
     * (국적·이미지·이메일은 이 화면에서 바꾸지 않는다 — 이미지는 별도 API, 그 외는 불변.)
     *
     * <p>{@code avatarHue}는 null이면 기존 값을 유지한다 — 색상을 보내지 않는 구버전 클라이언트가
     * 프로필을 저장할 때 색이 0으로 초기화되지 않게 하기 위함.
     */
    public void updateProfile(String nickname, String language, String bio, Integer avatarHue) {
        this.nickname = nickname;
        this.language = language;
        this.bio = bio;
        if (avatarHue != null) {
            this.avatarHue = avatarHue;
        }
    }

    /** 신분증 인증 승인 시 인증 배지를 부여한다. (verification 도메인 서비스에서 호출) */
    public void markVerified() {
        this.isVerified = true;
    }

    /**
     * 신뢰등급 반영 (이슈 #193). 산정 규칙은 {@code TrustGradeService.recalculate}(단일 진입점)에만 두고,
     * 엔티티는 의도가 드러나는 변경 메서드만 노출한다(@Setter 금지 원칙). updatedAt은 Auditing이 갱신.
     */
    public void applyTrustGrade(TrustGrade trustGrade) {
        this.trustGrade = trustGrade;
    }

    /** 탈퇴(soft delete): deleted_at만 세팅하고 실제 row는 보존한다. (community softDelete 패턴) */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 관리자 계정 상태 변경 (ACTIVE ↔ SUSPENDED). */
    public void changeStatus(MemberStatus status) {
        this.status = status;
    }

    // 커뮤니티 활동 제한 여부. false=정상, true=글/댓글 작성 차단.
    // ddl-auto:update가 신규 컬럼을 ADD하므로 기존 행은 DEFAULT(false)로 채워진다.
    @Column(name = "community_banned", nullable = false)
    @ColumnDefault("false")
    private boolean communityBanned = false;

    /** 관리자 커뮤니티 활동 제한/해제. */
    public void setCommunityBanned(boolean communityBanned) {
        this.communityBanned = communityBanned;
    }
}