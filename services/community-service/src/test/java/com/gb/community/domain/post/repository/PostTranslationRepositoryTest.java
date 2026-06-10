package com.gb.community.domain.post.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.entity.PostTranslation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link PostTranslationRepository} 검증 — 복합 PK (post_id, language) 조회/삭제/UNIQUE.
 *
 * <p>인메모리 H2(MySQL 호환 모드)에서 돌린다. {@code @DataJpaTest}는 기본 임베디드 DB로 대체하므로
 * {@code AutoConfigureTestDatabase(replace = NONE)}로 지정된 H2를 쓰게 한다(PostRepositoryTest와 동일).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PostTranslationRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PostTranslationRepository repository;

    private static final String U1 = "00000000-0000-0000-0000-000000000001";

    private Post post;

    @BeforeEach
    void setUp() {
        post = Post.of(U1, PostCategory.JOB, "ko", "최저임금 질문", "시급이 낮아요");
        em.persist(post);
        em.flush();
    }

    @Test
    @DisplayName("findByPostIdAndLanguage: 동일 (post, language) 조회 hit")
    void findByPostIdAndLanguage_hit() {
        em.persist(PostTranslation.of(post, "vi", "[VI] 최저임금", "[VI] 시급이 낮아요"));
        em.flush();
        em.clear();

        assertThat(repository.findByPostIdAndLanguage(post.getId(), "vi"))
                .isPresent()
                .get()
                .satisfies(t -> {
                    assertThat(t.getTranslatedTitle()).isEqualTo("[VI] 최저임금");
                    assertThat(t.getTranslatedContent()).isEqualTo("[VI] 시급이 낮아요");
                    assertThat(t.getLanguage()).isEqualTo("vi");
                    assertThat(t.getTranslatedAt()).isNotNull(); // @CreatedDate 자동 채움
                });
    }

    @Test
    @DisplayName("findByPostIdAndLanguage: 다른 언어/없는 post는 empty")
    void findByPostIdAndLanguage_miss() {
        em.persist(PostTranslation.of(post, "vi", "[VI] 최저임금", "[VI] 시급이 낮아요"));
        em.flush();
        em.clear();

        assertThat(repository.findByPostIdAndLanguage(post.getId(), "en")).isEmpty();
        assertThat(repository.findByPostIdAndLanguage(99999L, "vi")).isEmpty();
    }

    @Test
    @DisplayName("같은 (post, language) 중복 INSERT는 PK UNIQUE 위반")
    void 복합_PK_중복_금지() {
        em.persist(PostTranslation.of(post, "vi", "first", "first content"));
        em.flush();

        // 같은 (post_id, vi) 재INSERT → DataIntegrityViolation
        assertThatThrownBy(() -> {
            em.persist(PostTranslation.of(post, "vi", "second", "second content"));
            em.flush();
        }).isInstanceOf(Exception.class); // H2 → PersistenceException 또는 ConstraintViolation
    }

    @Test
    @DisplayName("같은 post에 다른 언어는 동시 보관 OK — 다국어 캐시 정책의 핵심")
    void 다국어_동시_보관() {
        em.persist(PostTranslation.of(post, "vi", "[VI] t", "[VI] c"));
        em.persist(PostTranslation.of(post, "en", "[EN] t", "[EN] c"));
        em.persist(PostTranslation.of(post, "fil", "[FIL] t", "[FIL] c"));
        em.flush();
        em.clear();

        assertThat(repository.findByPostIdAndLanguage(post.getId(), "vi")).isPresent();
        assertThat(repository.findByPostIdAndLanguage(post.getId(), "en")).isPresent();
        assertThat(repository.findByPostIdAndLanguage(post.getId(), "fil")).isPresent();
        assertThat(repository.findByPostIdAndLanguage(post.getId(), "ko")).isEmpty();
    }

    @Test
    @DisplayName("deleteByPostId: 해당 글의 모든 언어 캐시 일괄 삭제 — 본문 수정 시 무효화 시그니처")
    void deleteByPostId_모든_언어_삭제() {
        em.persist(PostTranslation.of(post, "vi", "[VI] t", "[VI] c"));
        em.persist(PostTranslation.of(post, "en", "[EN] t", "[EN] c"));
        em.flush();
        em.clear();

        repository.deleteByPostId(post.getId());
        em.flush();
        em.clear();

        assertThat(repository.findByPostIdAndLanguage(post.getId(), "vi")).isEmpty();
        assertThat(repository.findByPostIdAndLanguage(post.getId(), "en")).isEmpty();
    }

    @Test
    @DisplayName("deleteByPostId: 다른 글의 캐시는 영향 없음")
    void deleteByPostId_다른_글_무영향() {
        Post other = Post.of(U1, PostCategory.JOB, "ko", "다른 글", "다른 내용");
        em.persist(other);
        em.persist(PostTranslation.of(post, "vi", "[VI] t", "[VI] c"));
        em.persist(PostTranslation.of(other, "vi", "[VI] other", "[VI] other c"));
        em.flush();
        em.clear();

        repository.deleteByPostId(post.getId());
        em.flush();
        em.clear();

        assertThat(repository.findByPostIdAndLanguage(post.getId(), "vi")).isEmpty();
        assertThat(repository.findByPostIdAndLanguage(other.getId(), "vi")).isPresent();
    }
}
