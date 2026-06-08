package com.gb.admin.domain.auditLog.entity;

import com.gb.admin.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 감사 로그 — append-only(INSERT만).
 *
 * <p>한번 기록된 로그는 변경하지 않으므로 update 메서드/{@code @Setter}를 두지 않는다
 * (CLAUDE.md §4, wallet의 {@code TransactionAuditLog}와 동일 패턴).
 *
 * <p>{@code adminPublicId}는 admin-service 자체 도메인 식별자이지만, 회원 도메인 경계처럼 다른 서비스가
 * 본 컬럼을 참조할 일은 없다. {@code targetPublicId}는 대상 리소스의 public_id(member/wallet/community 등)로
 * MSA 경계를 넘는 값이라 문자열만 보유한다(물리 FK 없음).
 *
 * <p>{@code beforeSnapshot}/{@code afterSnapshot}은 JSON 문자열을 그대로 저장하는 감사용 raw 페이로드 —
 * 검색 용도가 아니라 사후 추적용이라 TEXT로 충분하다.
 */
@Entity
@Getter
@Table(name = "audit_logs",
        indexes = {
                @Index(name = "idx_audit_logs_admin_public_id", columnList = "admin_public_id"),
                @Index(name = "idx_audit_logs_action", columnList = "action"),
                @Index(name = "idx_audit_logs_target_type", columnList = "target_type"),
                @Index(name = "idx_audit_logs_created_at", columnList = "created_at")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    @Column(name = "admin_public_id", length = 36, nullable = false)
    private String adminPublicId;

    /** 행위 구분(KYC_APPROVE/KYC_REJECT/POST_HIDE/POST_DELETE 등). */
    @Column(name = "action", length = 50, nullable = false)
    private String action;

    /** 대상 리소스 타입(MEMBER/POST/TRANSACTION 등). */
    @Column(name = "target_type", length = 50, nullable = false)
    private String targetType;

    /** 대상 리소스의 public_id. MSA 경계를 넘으므로 문자열만 보유. */
    @Column(name = "target_public_id", length = 36)
    private String targetPublicId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "before_snapshot", columnDefinition = "TEXT")
    private String beforeSnapshot;

    @Column(name = "after_snapshot", columnDefinition = "TEXT")
    private String afterSnapshot;

    @Builder
    private AuditLog(String publicId, String adminPublicId, String action, String targetType,
                     String targetPublicId, String ipAddress, String beforeSnapshot, String afterSnapshot) {
        this.publicId = publicId;
        this.adminPublicId = adminPublicId;
        this.action = action;
        this.targetType = targetType;
        this.targetPublicId = targetPublicId;
        this.ipAddress = ipAddress;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
    }
}
