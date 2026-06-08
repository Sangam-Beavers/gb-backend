package com.gb.admin.domain.adminUser.entity;

import jakarta.persistence.Column;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자-역할 조인 엔티티. 한 관리자가 여러 role을 가질 수 있는 N:N를 표현한다.
 *
 * <p>같은 admin-service 스키마 내부 참조이므로 {@code admin_user_id}는 BIGINT FK + 단방향 {@code @ManyToOne}로
 * 매핑한다(CLAUDE.md §7 "내부는 BIGINT FK").
 */
@Entity
@Getter
@Table(name = "admin_user_roles",
        uniqueConstraints = @UniqueConstraint(name = "uk_admin_user_roles_user_role",
                columnNames = {"admin_user_id", "role"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminUserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_user_id", nullable = false)
    private AdminUser adminUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 30, nullable = false)
    private AdminRole role;

    @Builder
    private AdminUserRole(AdminUser adminUser, AdminRole role) {
        this.adminUser = adminUser;
        this.role = role;
    }
}
