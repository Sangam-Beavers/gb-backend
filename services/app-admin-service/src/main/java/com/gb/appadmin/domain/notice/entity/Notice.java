package com.gb.appadmin.domain.notice.entity;

import com.gb.appadmin.global.common.entity.BaseEntity;
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
 * 공지사항.
 * published=true 항목만 사용자에게 노출된다.
 */
@Entity
@Getter
@Table(name = "notices",
        indexes = {
                @Index(name = "idx_notices_published", columnList = "published"),
                @Index(name = "idx_notices_pinned", columnList = "pinned"),
                @Index(name = "idx_notices_created_at", columnList = "created_at")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /** true: 목록 상단 고정 */
    @Column(name = "pinned", nullable = false)
    private Boolean pinned;

    /** false: 초안 상태(사용자에게 미노출) */
    @Column(name = "published", nullable = false)
    private Boolean published;

    @Builder
    private Notice(String publicId, String title, String content, Boolean pinned, Boolean published) {
        this.publicId = publicId;
        this.title = title;
        this.content = content;
        this.pinned = pinned != null ? pinned : false;
        this.published = published != null ? published : false;
    }

    public void update(String title, String content, Boolean pinned, Boolean published) {
        if (title != null) this.title = title;
        if (content != null) this.content = content;
        if (pinned != null) this.pinned = pinned;
        if (published != null) this.published = published;
    }
}
