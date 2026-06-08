package com.gb.community.domain.comment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 댓글 번역 캐시 — {@link Comment} 1건에 대해 언어별로 1행을 보관한다.
 * 게시글({@code PostTranslation})과 동일 패턴이며, 댓글은 제목이 없어 {@code translated_content}만 보관한다.
 *
 * <p>복합 PK {@code (comment_id, language)} — 다국어 동시 보관. 본문 수정 시 Service가
 * {@code deleteByCommentId}를 명시 호출(DB CASCADE 미사용). 상세: {@code docs/community/translation.md}.
 */
@Entity
@Getter
@Table(name = "comment_translations")
@IdClass(CommentTranslation.CommentTranslationId.class)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommentTranslation {

    /** 댓글 참조. 같은 community 스키마 내부 객체 매핑 (CLAUDE.md §4). */
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id", nullable = false)
    private Comment comment;

    /** 번역 대상 언어 코드 (`ko`/`en`/`vi`/`fil`). */
    @Id
    @Column(name = "language", length = 10, nullable = false)
    private String language;

    @Lob
    @Column(name = "translated_content", nullable = false, columnDefinition = "TEXT")
    private String translatedContent;

    @CreatedDate
    @Column(name = "translated_at", nullable = false, updatable = false)
    private LocalDateTime translatedAt;

    @Builder
    private CommentTranslation(Comment comment, String language, String translatedContent) {
        this.comment = comment;
        this.language = language;
        this.translatedContent = translatedContent;
    }

    /** Request → Entity 변환 정적 팩토리 (CLAUDE.md §4). */
    public static CommentTranslation of(Comment comment, String language, String translatedContent) {
        return CommentTranslation.builder()
                .comment(comment)
                .language(language)
                .translatedContent(translatedContent)
                .build();
    }

    @NoArgsConstructor
    public static class CommentTranslationId implements Serializable {
        private Long comment;    // = Comment.id
        private String language;

        public CommentTranslationId(Long comment, String language) {
            this.comment = comment;
            this.language = language;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CommentTranslationId that)) return false;
            return Objects.equals(comment, that.comment) && Objects.equals(language, that.language);
        }

        @Override
        public int hashCode() {
            return Objects.hash(comment, language);
        }
    }
}
