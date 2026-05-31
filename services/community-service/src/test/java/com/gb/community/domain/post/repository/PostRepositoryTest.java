package com.gb.community.domain.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
 * {@link PostRepository#search} 및 {@link PostRepository#findByPublicIdAndDeletedAtIsNull} 검증.
 *
 * <p>인메모리 H2(MySQL 호환 모드)에서 돌린다({@code application-test.yml}). 검색·카테고리 필터·정렬·
 * 페이지네이션뿐 아니라 <b>필터링</b>(삭제글/다른 카테고리가 결과에서 빠지는지)도 검증한다.
 *
 * <p>{@code created_at}/{@code like_count}는 persist 후 native UPDATE로 직접 박는다 —
 * {@code @CreatedDate}는 {@code @PrePersist}에서 now()로 덮어쓰고, like_count는 도메인 메서드가 없어
 * 빌더 기본값(0)만 가능하기 때문(정렬을 결정적으로 만들기 위함). wallet TransactionRepositoryTest와 동일 기법.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PostRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PostRepository postRepository;

    private static final String U1 = "00000000-0000-0000-0000-000000000001";
    private static final String U2 = "00000000-0000-0000-0000-000000000002";
    private static final String U3 = "00000000-0000-0000-0000-000000000003";

    // 일 단위로 충분히 벌려 정렬·필터 의도를 분명히 한다.
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 5, 21, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 5, 22, 10, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 5, 23, 10, 0);
    private static final LocalDateTime T4 = LocalDateTime.of(2026, 5, 24, 10, 0);
    private static final LocalDateTime T5 = LocalDateTime.of(2026, 5, 25, 10, 0);
    private static final LocalDateTime T6 = LocalDateTime.of(2026, 5, 26, 10, 0);

    private Post p1; // U1 JOB      like=1  T1
    private Post p2; // U2 VISA     like=5  T2  (제목에 "비자")
    private Post p3; // U1 JOB      like=3  T3
    private Post p4; // U3 QUESTION like=2  T4  (본문에 "비자")
    private Post p5; // U2 JOB      like=99 T5  삭제됨 (결과에서 빠져야 함; 본문에 "비자")

    private static final Sort LATEST = Sort.by(Direction.DESC, "createdAt", "id");
    private static final Sort POPULAR = Sort.by(Direction.DESC, "likeCount", "createdAt", "id");

    @BeforeEach
    void setUp() {
        p1 = persistPost(U1, PostCategory.JOB, "최저임금 질문", "시급이 낮아요", T1, 1, false);
        p2 = persistPost(U2, PostCategory.VISA, "비자 변경 문의", "E-9 사업장 변경", T2, 5, false);
        p3 = persistPost(U1, PostCategory.JOB, "임금체불 신고", "월급을 못 받았어요", T3, 3, false);
        p4 = persistPost(U3, PostCategory.QUESTION, "계약서 작성", "비자 관련 계약 검토", T4, 2, false);
        p5 = persistPost(U2, PostCategory.JOB, "삭제된 글", "비자 내용 포함", T5, 99, true);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("최신순 전체 조회: 삭제글(p5) 제외, createdAt DESC")
    void search_최신순_삭제글_제외() {
        Page<Post> page = postRepository.search(null, null, PageRequest.of(0, 20, LATEST));

        assertThat(page.getContent()).extracting(Post::getPublicId)
                .containsExactly(p4.getPublicId(), p3.getPublicId(), p2.getPublicId(), p1.getPublicId());
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("카테고리 필터(JOB): 활성 JOB(p1,p3)만 — 삭제된 JOB(p5)·다른 카테고리 제외")
    void search_카테고리_필터() {
        Page<Post> page = postRepository.search(PostCategory.JOB, null, PageRequest.of(0, 20, LATEST));

        assertThat(page.getContent()).extracting(Post::getPublicId)
                .containsExactly(p3.getPublicId(), p1.getPublicId());
    }

    @Test
    @DisplayName("키워드 '비자': 제목(p2)·본문(p4) 매칭, 삭제글(p5)은 매칭돼도 제외")
    void search_키워드_제목_본문() {
        Page<Post> page = postRepository.search(null, "비자", PageRequest.of(0, 20, LATEST));

        assertThat(page.getContent()).extracting(Post::getPublicId)
                .containsExactly(p4.getPublicId(), p2.getPublicId());
    }

    @Test
    @DisplayName("인기순: 활성 글 likeCount DESC (p2=5, p3=3, p4=2, p1=1)")
    void search_인기순() {
        Page<Post> page = postRepository.search(null, null, PageRequest.of(0, 20, POPULAR));

        assertThat(page.getContent()).extracting(Post::getPublicId)
                .containsExactly(p2.getPublicId(), p3.getPublicId(), p4.getPublicId(), p1.getPublicId());
    }

    @Test
    @DisplayName("인기순 동률: like_count가 같으면 createdAt DESC tie-break — 같은 5점인 p6(신)·p2(구) 중 p6 먼저")
    void search_인기순_동률_tie_break() {
        // p2와 동일한 like=5이지만 더 최근(T6 > T2)인 글을 추가 — likeCount 단독 정렬이면 순서가 비결정적.
        Post p6 = persistPost(U3, PostCategory.LIFE_INFO, "동률 글", "내용", T6, 5, false);
        em.flush();
        em.clear();

        Page<Post> page = postRepository.search(null, null, PageRequest.of(0, 20, POPULAR));

        // like DESC, 동률(p6=p2=5)은 createdAt DESC → p6(T6) 먼저, 그다음 p2(T2)
        assertThat(page.getContent()).extracting(Post::getPublicId)
                .containsExactly(p6.getPublicId(), p2.getPublicId(),
                        p3.getPublicId(), p4.getPublicId(), p1.getPublicId());
    }

    @Test
    @DisplayName("카테고리+키워드 동시 필터: JOB && '임금' → p1,p3만 (VISA/QUESTION·삭제글 제외)")
    void search_카테고리_키워드_동시() {
        Page<Post> page = postRepository.search(PostCategory.JOB, "임금", PageRequest.of(0, 20, LATEST));

        // p1 제목 "최저임금 질문", p3 제목 "임금체불 신고" — 둘 다 JOB. p2(VISA)·p4(QUESTION)·p5(삭제) 제외.
        assertThat(page.getContent()).extracting(Post::getPublicId)
                .containsExactly(p3.getPublicId(), p1.getPublicId());
    }

    @Test
    @DisplayName("매칭 없는 키워드: 빈 결과 + total 0")
    void search_키워드_매칭없음() {
        Page<Post> page = postRepository.search(null, "존재하지않는키워드XYZ", PageRequest.of(0, 20, LATEST));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("키워드 LIKE 이스케이프: '%'를 와일드카드가 아닌 literal로 매칭(ESCAPE 절)")
    void search_키워드_퍼센트_literal_매칭() {
        Post pA = persistPost(U1, PostCategory.LIFE_INFO, "100% 환급 보장", "내용", T6, 0, false);
        Post pB = persistPost(U2, PostCategory.LIFE_INFO, "1000원 행사 안내", "내용", T6, 0, false);
        em.flush();
        em.clear();

        // 서비스가 넘기는 이스케이프 형태("100|%", ESCAPE '|') — literal '%'만 매칭하므로 pA만. (이스케이프 없으면 pB도 매칭)
        Page<Post> literal = postRepository.search(null, "100|%", PageRequest.of(0, 20, LATEST));
        assertThat(literal.getContent()).extracting(Post::getPublicId)
                .containsExactly(pA.getPublicId());

        // 일반 부분일치("100")는 둘 다 매칭 — 정상 검색 회귀 방지.
        Page<Post> plain = postRepository.search(null, "100", PageRequest.of(0, 20, LATEST));
        assertThat(plain.getContent()).extracting(Post::getPublicId)
                .contains(pA.getPublicId(), pB.getPublicId());
    }

    @Test
    @DisplayName("페이지네이션: size=2, 최신순 — page0=[p4,p3], page1=[p2,p1], total=4/2페이지")
    void search_페이지네이션() {
        Page<Post> page0 = postRepository.search(null, null, PageRequest.of(0, 2, LATEST));
        assertThat(page0.getContent()).extracting(Post::getPublicId)
                .containsExactly(p4.getPublicId(), p3.getPublicId());
        assertThat(page0.getTotalElements()).isEqualTo(4);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getNumber()).isZero();

        Page<Post> page1 = postRepository.search(null, null, PageRequest.of(1, 2, LATEST));
        assertThat(page1.getContent()).extracting(Post::getPublicId)
                .containsExactly(p2.getPublicId(), p1.getPublicId());
    }

    @Test
    @DisplayName("findByPublicIdAndDeletedAtIsNull: 활성글은 조회, 삭제글·없는 키는 empty")
    void findByPublicIdAndDeletedAtIsNull_조회() {
        assertThat(postRepository.findByPublicIdAndDeletedAtIsNull(p1.getPublicId())).isPresent();
        assertThat(postRepository.findByPublicIdAndDeletedAtIsNull(p5.getPublicId())).isEmpty();
        assertThat(postRepository.findByPublicIdAndDeletedAtIsNull("no-such-uuid")).isEmpty();
    }

    // ----- helpers -----

    /**
     * 게시글 1건 영속화 후 native UPDATE로 created_at/like_count를 지정값으로 덮어쓴다.
     * (JPA 경로로는 auditing/빌더 기본값 때문에 통제 불가 — 클래스 주석 참고)
     */
    private Post persistPost(String userPublicId, PostCategory category, String title, String content,
                             LocalDateTime createdAt, int likeCount, boolean deleted) {
        Post p = Post.of(userPublicId, category, title, content);
        if (deleted) {
            p.softDelete();
        }
        em.persist(p); // IDENTITY → INSERT 즉시 실행되어 id 채워짐
        em.getEntityManager()
                .createNativeQuery("UPDATE posts SET created_at = ?1, like_count = ?2 WHERE id = ?3")
                .setParameter(1, createdAt)
                .setParameter(2, likeCount)
                .setParameter(3, p.getId())
                .executeUpdate();
        return p;
    }
}