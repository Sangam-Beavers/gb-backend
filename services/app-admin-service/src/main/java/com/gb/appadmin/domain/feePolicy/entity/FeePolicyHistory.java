package com.gb.appadmin.domain.feePolicy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import jakarta.persistence.EntityListeners;

/**
 * 수수료 정책 변경 이력 — append-only.
 */
@Entity
@Getter
@Table(name = "fee_policy_histories",
        indexes = {
                @Index(name = "idx_fee_policy_hist_policy_id", columnList = "fee_policy_id"),
                @Index(name = "idx_fee_policy_hist_changed_at", columnList = "changed_at")
        })
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeePolicyHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fee_policy_id", nullable = false)
    private FeePolicy feePolicy;

    @Column(name = "changed_by_admin_public_id", length = 36, nullable = false)
    private String changedByAdminPublicId;

    @Column(name = "before_snapshot", columnDefinition = "TEXT")
    private String beforeSnapshot;

    @Column(name = "after_snapshot", columnDefinition = "TEXT")
    private String afterSnapshot;

    @CreatedDate
    @Column(name = "changed_at", updatable = false)
    private LocalDateTime changedAt;

    @Builder
    private FeePolicyHistory(FeePolicy feePolicy, String changedByAdminPublicId,
                             String beforeSnapshot, String afterSnapshot) {
        this.feePolicy = feePolicy;
        this.changedByAdminPublicId = changedByAdminPublicId;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
    }
}
