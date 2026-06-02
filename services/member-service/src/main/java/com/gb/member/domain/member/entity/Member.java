package com.gb.member.domain.member.entity;

import com.gb.member.global.common.entity.BaseEntity;
import jakarta.persistence.*;
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
                  String authProviderId) {
        this.publicId = publicId;
        this.email = email;
        this.name = name;
        this.nickname = nickname;
        this.nationality = nationality;
        this.language = language;
        this.authProviderId = authProviderId;
    }

    /** 주 사용 언어 변경. updatedAt은 Auditing(dirty checking)으로 자동 갱신된다. */
    public void changeLanguage(String language) {
        this.language = language;
    }

    /** 탈퇴(soft delete): deleted_at만 세팅하고 실제 row는 보존한다. (community softDelete 패턴) */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}