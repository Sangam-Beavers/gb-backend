package com.gb.community.domain.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link CommentRepository#findByPostAndDeletedAtIsNull} 검증 — 인메모리 H2(MySQL 호환 모드).
 *
 * <p>핵심은 작성순(createdAt ASC) 정렬·페이지네이션뿐 아니라 <b>필터링</b>(삭제 댓글·다른 게시글 댓글이
 * 결과에서 빠지는지)이다(CLAUDE.md §10 — 제외돼야 할 데이터가 안 나오는지 검증).
 *
 * <p>{@code created_at}는 persist 후 native UPDATE로 직접 박는다 — {@code @CreatedDate}는 persist 시
 * now()로 채워질 수 있어, 정렬을 결정적으로 만들기 위함(PostRepositoryTest와 동일 기법).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CommentRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private CommentRepository commentRepository;

    private static final String U1 = "00000000-0000-0000-0000-000000000001";
    private static final String U2 = "00000000-0000-0000-0000-000000000002";
    private static final String U3 = "00000000-0000-0000-0000-000000000003";

    // 작성 시각 — 일 단위로 벌려 작성순(ASC) 의도를 분명히 한다.
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 5, 21, 10, 0); // 가장 과거
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 5, 22, 10, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 5, 23, 10, 0); // 가장 최근
    private static final LocalDateTime T4 = LocalDateTime.of(2026, 5, 24, 10, 0);

    private static final Sort OLDEST_FIRST = Sort.by(Direction.ASC, "createdAt", "id");

    private Post target;  // 조회 대상 게시글
    private Post other;   // 다른 게시글 — 이 글의 댓글은 target 조회에서 빠져야 함
    private Post empty;    // 댓글 없는 게시글

    private Comment c1; // target, T1
    private Comment c2; // target, T2
    private Comment c3; // target, T3
    private Comment cDeleted; // target, T4, soft delete → 제외
    private Comment cOther;   // other 게시글 댓글 → target 조회에서 제외

    @BeforeEach
    void setUp() {
        target = persistPost(U1, PostCategory.JOB, "시급 미달", "내용");
        other = persistPost(U2, PostCategory.VISA, "비자 변경", "내용");
        empty = persistPost(U3, PostCategory.QUESTION, "댓글 없는 글", "내용");

        // 작성순 정렬이 id가 아니라 createdAt 기준임을 보이려고, 시간 역순으로 insert(id는 c2<c3<c1 순서가 되도록).
        c2 = persistComment(target, U3, "두 번째 작성 댓글", T2, false);
        c3 = persistComment(target, U1, "세 번째 작성 댓글", T3, false);
        c1 = persistComment(target, U2, "첫 번째 작성 댓글", T1, false);
        cDeleted = persistComment(target, U2, "삭제된 댓글", T4, true);
        cOther = persistComment(other, U1, "다른 글 댓글", T1, false);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("작성순(ASC) 조회: target의 활성 댓글만 createdAt ASC — 삭제 댓글·다른 글 댓글 제외")
    void 작성순_정렬_필터() {
        Page<Comment> page =
                commentRepository.findByPostAndDeletedAtIsNull(target, PageRequest.of(0, 20, OLDEST_FIRST));

        // createdAt ASC → c1(T1), c2(T2), c3(T3). id 순서(c2<c3<c1)와 다름을 확인해 createdAt 정렬임을 입증.
        assertThat(page.getContent()).extracting(Comment::getPublicId)
                .containsExactly(c1.getPublicId(), c2.getPublicId(), c3.getPublicId());
        assertThat(page.getTotalElements()).isEqualTo(3); // 삭제 댓글(cDeleted)·다른 글(cOther) 제외
        assertThat(page.getContent()).extracting(Comment::getContent)
                .doesNotContain("삭제된 댓글", "다른 글 댓글");
    }

    @Test
    @DisplayName("게시글 스코프: other 게시글 조회는 그 글의 댓글(cOther)만 — target 댓글 제외")
    void 게시글_스코프_격리() {
        Page<Comment> page =
                commentRepository.findByPostAndDeletedAtIsNull(other, PageRequest.of(0, 20, OLDEST_FIRST));

        assertThat(page.getContent()).extracting(Comment::getPublicId)
                .containsExactly(cOther.getPublicId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("댓글 없는 게시글: 빈 목록 + total 0")
    void 빈_목록() {
        Page<Comment> page =
                commentRepository.findByPostAndDeletedAtIsNull(empty, PageRequest.of(0, 20, OLDEST_FIRST));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("페이지네이션: size=2, 작성순 — page0=[c1,c2], page1=[c3], total=3/2페이지")
    void 페이지네이션() {
        Page<Comment> page0 =
                commentRepository.findByPostAndDeletedAtIsNull(target, PageRequest.of(0, 2, OLDEST_FIRST));
        assertThat(page0.getContent()).extracting(Comment::getPublicId)
                .containsExactly(c1.getPublicId(), c2.getPublicId());
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getNumber()).isZero();

        Page<Comment> page1 =
                commentRepository.findByPostAndDeletedAtIsNull(target, PageRequest.of(1, 2, OLDEST_FIRST));
        assertThat(page1.getContent()).extracting(Comment::getPublicId)
                .containsExactly(c3.getPublicId());
    }

    @Test
    @DisplayName("동률 tie-break: 같은 createdAt이면 id ASC로 결정적 정렬")
    void 동률_id_tie_break() {
        // 같은 시각(T2)에 작성된 댓글 2건을 추가 — createdAt 단독 정렬이면 순서가 비결정적.
        Comment a = persistComment(target, U1, "동시각 A", T2, false);
        Comment b = persistComment(target, U2, "동시각 B", T2, false);
        em.flush();
        em.clear();

        Page<Comment> page =
                commentRepository.findByPostAndDeletedAtIsNull(target, PageRequest.of(0, 20, OLDEST_FIRST));

        // T2 동률(c2, a, b)은 id ASC. c2가 a/b보다 먼저 insert돼 id가 작으므로 c2 → a → b 순.
        assertThat(page.getContent()).extracting(Comment::getPublicId)
                .containsExactly(c1.getPublicId(), c2.getPublicId(), a.getPublicId(),
                        b.getPublicId(), c3.getPublicId());
    }

    @Test
    @DisplayName("softDeleteByPublicId: 활성 댓글 첫 호출=1행, 이미 삭제된 댓글 재호출=0행 — comment_count 과차감 게이트(COM1)")
    void softDeleteByPublicId_영향행_게이트() {
        String pid = c1.getPublicId(); // setUp의 활성 댓글
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);

        int first = commentRepository.softDeleteByPublicId(pid, now);
        // 같은 행을 다시 삭제 시도 — deleted_at IS NULL 조건 불일치(동시 중복 삭제의 '패자'가 받는 값).
        int second = commentRepository.softDeleteByPublicId(pid, now);

        assertThat(first).as("활성 댓글 → 1건 soft delete").isEqualTo(1);
        assertThat(second).as("이미 삭제 → 0건. 서비스는 1일 때만 comment_count -1 하므로 과차감 방지").isZero();
        // 실제로 deleted_at이 박혀 활성 단건 조회에서 빠지는지 확인.
        assertThat(commentRepository.findByPublicIdAndDeletedAtIsNull(pid)).isEmpty();
        // 한계: 진짜 동시성(두 트랜잭션 동시 진입)은 H2로 재현 불가 — 조건부 UPDATE의 affected-row 의미만 순차 검증.
    }

    // ----- helpers -----

    private Post persistPost(String userPublicId, PostCategory category, String title, String content) {
        Post p = Post.of(userPublicId, category, title, content);
        em.persist(p);
        return p;
    }

    /** 댓글 1건 영속화 후 native UPDATE로 created_at을 지정값으로 박는다(정렬을 결정적으로). */
    private Comment persistComment(Post post, String userPublicId, String content,
                                   LocalDateTime createdAt, boolean deleted) {
        Comment c = Comment.builder()
                .post(post)
                .userPublicId(userPublicId)
                .content(content)
                .build();
        if (deleted) {
            c.softDelete();
        }
        em.persist(c); // IDENTITY → INSERT 즉시 실행되어 id 채워짐
        em.getEntityManager()
                .createNativeQuery("UPDATE comments SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, c.getId())
                .executeUpdate();
        return c;
    }
}
