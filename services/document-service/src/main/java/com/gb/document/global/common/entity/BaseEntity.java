package com.gb.document.global.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 생성/수정 시각을 공통 제공하는 매핑 슈퍼클래스.
 * JPA Auditing이 활성화되어야 동작한다(JpaConfig의 @EnableJpaAuditing).
 *
 * <p>CLAUDE.md §4 — 공통 시각 필드는 BaseEntity로 분리, 모든 엔티티가 상속.
 * <p>wallet-service의 동일 클래스와 패턴 일치(코드 일관성).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
