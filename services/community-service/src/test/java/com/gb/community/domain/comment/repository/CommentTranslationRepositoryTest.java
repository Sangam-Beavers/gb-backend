package com.gb.community.domain.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.entity.CommentTranslation;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
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
 * {@link CommentTranslationRepository} 검증 — PostTranslationRepositoryTest와 동일 패턴(댓글에 제목 없음).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CommentTranslationRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private CommentTranslationRepository repository;

    private static final String U1 = "00000000-0000-0000-0000-000000000001";

    private Comment comment;

    @BeforeEach
    void setUp() {
        Post post = Post.of(U1, PostCategory.JOB, "ko", "title", "content");
        em.persist(post);
        comment = Comment.builder().post(post).userPublicId(U1).parentId(null).content("댓글 본문").build();
        em.persist(comment);
        em.flush();
    }

    @Test
    @DisplayName("findByCommentIdAndLanguage: hit")
    void hit() {
        em.persist(CommentTranslation.of(comment, "vi", "[VI] 댓글 본문"));
        em.flush();
        em.clear();

        assertThat(repository.findByCommentIdAndLanguage(comment.getId(), "vi"))
                .isPresent()
                .get()
                .satisfies(t -> {
                    assertThat(t.getTranslatedContent()).isEqualTo("[VI] 댓글 본문");
                    assertThat(t.getLanguage()).isEqualTo("vi");
                    assertThat(t.getTranslatedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("findByCommentIdAndLanguage: 다른 언어 miss")
    void miss() {
        em.persist(CommentTranslation.of(comment, "vi", "[VI] 댓글 본문"));
        em.flush();
        em.clear();

        assertThat(repository.findByCommentIdAndLanguage(comment.getId(), "en")).isEmpty();
    }

    @Test
    @DisplayName("같은 (comment, language) 중복 INSERT는 PK UNIQUE 위반")
    void 복합_PK_중복_금지() {
        em.persist(CommentTranslation.of(comment, "vi", "first"));
        em.flush();
        assertThatThrownBy(() -> {
            em.persist(CommentTranslation.of(comment, "vi", "second"));
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("deleteByCommentId: 모든 언어 일괄 삭제")
    void deleteByCommentId_모든_언어() {
        em.persist(CommentTranslation.of(comment, "vi", "[VI]"));
        em.persist(CommentTranslation.of(comment, "en", "[EN]"));
        em.flush();
        em.clear();

        repository.deleteByCommentId(comment.getId());
        em.flush();
        em.clear();

        assertThat(repository.findByCommentIdAndLanguage(comment.getId(), "vi")).isEmpty();
        assertThat(repository.findByCommentIdAndLanguage(comment.getId(), "en")).isEmpty();
    }
}
