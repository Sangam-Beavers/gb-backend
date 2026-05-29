package com.gb.community.global.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * Soft delete가 적용되는 엔티티의 공통 슈퍼클래스 (BaseEntity 확장).
 * docs/database.md "공통 컬럼 규약 — BaseSoftDeleteEntity" 그대로.
 *
 * <p>적용 대상: posts, comments (community 도메인). users는 member 도메인 소관.
 *
 * <p>{@code deletedAt}이 null이면 활성, 값 있으면 삭제됨. 조회 시 반드시
 * {@code WHERE deleted_at IS NULL} 조건을 명시해 삭제된 row를 제외한다.
 */
@Getter
@MappedSuperclass
public abstract class BaseSoftDeleteEntity extends BaseEntity {

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 도메인 메서드로 soft delete 처리. 외부에서 setter로 임의 변경 금지. */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
