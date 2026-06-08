package com.gb.admin.domain.adminUser.entity;

import com.gb.admin.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 사용자. 외부 IdP(Authentik/Cognito)가 인증을 담당하므로 본 테이블의 {@code hashedPassword}는
 * 항상 null이며, IdP attribute의 {@code public_id}를 본 컬럼의 {@code publicId}와 1:1로 매핑한다.
 *
 * <p>{@code id}(BIGINT) 는 내부 PK이고 외부 노출은 {@code publicId}(UUID)만 사용한다(conventions §5).
 *
 * <p>여러 role을 가질 수 있어 {@link AdminUserRole} 조인 엔티티로 N:N를 표현하되, 본 PR은 단일 사용자
 * 시드(admin01 → SUPER)만 둔다.
 */
@Entity
@Getter
@Table(name = "admin_users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminUser extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출 식별자. IdP attribute의 public_id와 일치(JWT claim public_id 검증 키). */
    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    @Column(name = "email", length = 255, nullable = false, unique = true)
    private String email;

    @Column(name = "nickname", length = 100, nullable = false)
    private String nickname;

    /** IdP가 비밀번호를 관리하므로 null. 향후 로컬 폴백이 필요해지면 채워 둔다(현재 미사용). */
    @Column(name = "hashed_password", length = 255)
    private String hashedPassword;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Builder
    private AdminUser(String publicId, String email, String nickname, String hashedPassword, boolean isActive) {
        this.publicId = publicId;
        this.email = email;
        this.nickname = nickname;
        this.hashedPassword = hashedPassword;
        this.isActive = isActive;
    }
}
