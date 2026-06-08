package com.gb.community.domain.post.entity;

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
 * 게시글 번역 캐시 — {@link Post} 1건에 대해 언어별로 1행을 보관한다.
 *
 * <p>복합 PK {@code (post_id, language)} — 다국어를 동시에 보관해 사용자별 언어 차이를
 * 캐시 hit로 흡수한다(구버전의 {@code posts.translated_*} 단일 컬럼은 한 글에 한 언어만 보관해
 * 사용자가 바뀌면 매번 캐시 미스가 났다 — #161에서 제거).
 *
 * <p>{@link IdClass} 패턴: JPA의 복합 PK 표현 두 방식({@code @IdClass} vs {@code @EmbeddedId}) 중
 * {@code @IdClass}를 채택. 이유:
 * <ul>
 *   <li>엔티티 필드를 평탄하게 접근(`post`/`language` 직접) — 임베디드 PK 객체를 따로 안 만든다.</li>
 *   <li>Repository 메서드 네이밍이 단순({@code findByPostIdAndLanguage} — id 경로 명확).</li>
 *   <li>{@code @ManyToOne}로 매핑된 키 컬럼은 {@code @IdClass}와 자연스럽게 호환.</li>
 * </ul>
 *
 * <p>번역 결과 저장 방향: lazy(사용자가 "번역 보기" 클릭 시) → Bedrock Claude Haiku(계정 B Lambda) 호출
 * → 본 엔티티 INSERT. 캐시 무효화는 본문/제목 수정 시 Service가 명시적으로 {@code deleteByPostId}를
 * 호출(엔티티 cascade에 의존하지 않음). 상세: {@code docs/community/translation.md}.
 *
 * <p>{@code BaseEntity}를 상속하지 않는 이유: 본 캐시는 INSERT-OR-DELETE 패턴(UPDATE 없음 — 본문이 바뀌면
 * 삭제 후 재생성)이라 {@code updated_at}이 무의미하다. {@code translated_at} 1컬럼만 {@code @CreatedDate}로 둔다.
 */
@Entity
@Getter
@Table(name = "post_translations")
@IdClass(PostTranslation.PostTranslationId.class)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostTranslation {

    /**
     * 게시글 참조. 같은 community 스키마 내부라 객체 매핑 OK (CLAUDE.md §4).
     *
     * <p>{@code @Id}와 함께 두면 JPA가 join column을 PK 일부로 인식한다 — Repository의
     * {@code findByPost_IdAndLanguage}는 평탄한 {@code findByPostIdAndLanguage} 형태로 둘 다 유효하지만,
     * 본 프로젝트는 후자(언더스코어 없는) 네이밍을 채택해 호출 측이 깔끔하다.
     */
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    /** 번역 대상 언어 코드 (`ko`/`en`/`vi`/`fil`). Service에서 화이트리스트 검증 후 진입. */
    @Id
    @Column(name = "language", length = 10, nullable = false)
    private String language;

    @Column(name = "translated_title", length = 255, nullable = false)
    private String translatedTitle;

    @Lob
    @Column(name = "translated_content", nullable = false, columnDefinition = "TEXT")
    private String translatedContent;

    /**
     * 번역 캐시 INSERT 시각. {@code @CreatedDate}만 — 본 엔티티는 INSERT-OR-DELETE 패턴이라
     * UPDATE가 발생하지 않는다(본문이 바뀌면 행 자체를 삭제하고 재INSERT). 따라서 {@code @LastModifiedDate} 불요.
     */
    @CreatedDate
    @Column(name = "translated_at", nullable = false, updatable = false)
    private LocalDateTime translatedAt;

    @Builder
    private PostTranslation(Post post, String language, String translatedTitle, String translatedContent) {
        this.post = post;
        this.language = language;
        this.translatedTitle = translatedTitle;
        this.translatedContent = translatedContent;
    }

    /** Request → Entity 변환 정적 팩토리 (CLAUDE.md §4). */
    public static PostTranslation of(Post post, String language, String translatedTitle, String translatedContent) {
        return PostTranslation.builder()
                .post(post)
                .language(language)
                .translatedTitle(translatedTitle)
                .translatedContent(translatedContent)
                .build();
    }

    // ----- IdClass: 복합 PK 동등성 정의 -----

    /**
     * JPA {@code @IdClass} 명세상 별도의 PK 클래스가 필요하다. 필드명·타입은
     * 엔티티의 {@code @Id} 선언과 정확히 일치해야 한다:
     * <ul>
     *   <li>{@code post}: 연관 PK는 그 엔티티의 PK 타입({@code Long})으로 표현 — JPA 명세.</li>
     *   <li>{@code language}: 엔티티와 동일한 {@code String}.</li>
     * </ul>
     * Serializable + equals/hashCode 구현 필수(영속성 컨텍스트 식별 키).
     */
    @NoArgsConstructor
    public static class PostTranslationId implements Serializable {
        private Long post;       // = Post.id
        private String language;

        public PostTranslationId(Long post, String language) {
            this.post = post;
            this.language = language;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PostTranslationId that)) return false;
            return Objects.equals(post, that.post) && Objects.equals(language, that.language);
        }

        @Override
        public int hashCode() {
            return Objects.hash(post, language);
        }
    }
}
