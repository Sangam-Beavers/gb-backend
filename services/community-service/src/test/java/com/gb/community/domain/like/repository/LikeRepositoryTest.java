package com.gb.community.domain.like.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gb.community.domain.like.entity.Like;
import com.gb.community.domain.like.entity.LikeTargetType;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link LikeRepository} 검증 — 인메모리 H2(MySQL 호환 모드).
 *
 * <p>핵심은 likes↔posts theta join + 인터페이스 프로젝션({@link LikedPostProjection})이 H2에서 동작하는지,
 * 그리고 필터링(삭제글·다른 사용자·target_type=COMMENT가 결과에서 빠지는지)·정렬(latest/popular)·
 * 페이지네이션·UNIQUE 제약이 의도대로인지다(CLAUDE.md §10 방언/필터링 검증).
 *
 * <p>{@code created_at}(좋아요 누른 시각)는 persist 후 native UPDATE로 직접 박는다 — @CreatedDate/auditing에
 * 의존하지 않고 정렬을 결정적으로 만들기 위함(PostRepositoryTest와 동일 기법).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class LikeRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private LikeRepository likeRepository;

    private static final String U1 = "00000000-0000-0000-0000-000000000001"; // 조회 주체
    private static final String U2 = "00000000-0000-0000-0000-000000000002"; // 다른 사용자

    // 좋아요 누른 시각 — 일 단위로 벌려 latest 정렬 의도를 분명히 한다.
    private static final LocalDateTime L1 = LocalDateTime.of(2026, 5, 21, 10, 0); // pA (가장 과거)
    private static final LocalDateTime L2 = LocalDateTime.of(2026, 5, 22, 10, 0); // pB
    private static final LocalDateTime L3 = LocalDateTime.of(2026, 5, 23, 10, 0); // pC (가장 최근)
    private static final LocalDateTime L4 = LocalDateTime.of(2026, 5, 24, 10, 0); // pDeleted
    private static final LocalDateTime L5 = LocalDateTime.of(2026, 5, 25, 10, 0); // COMMENT 좋아요

    private Post pA;       // like_count=1, U1 좋아요(L1)
    private Post pB;       // like_count=5, U1 좋아요(L2) + U2 좋아요
    private Post pC;       // like_count=3, U1 좋아요(L3)
    private Post pDeleted; // like_count=2, 삭제됨, U1 좋아요(L4) → 결과에서 제외돼야 함
    private Post pE;       // like_count=9, U2만 좋아요 → U1 결과에 없어야 함

    @BeforeEach
    void setUp() {
        pA = persistPost(U1, PostCategory.JOB, "시급 미달", "내용A", 1, false);
        pB = persistPost(U2, PostCategory.VISA, "비자 변경", "내용B", 5, false);
        pC = persistPost(U1, PostCategory.JOB, "임금체불", "내용C", 3, false);
        pDeleted = persistPost(U2, PostCategory.JOB, "삭제글", "내용D", 2, true);
        pE = persistPost(U2, PostCategory.QUESTION, "U2만 좋아요", "내용E", 9, false);

        // U1의 게시글 좋아요 (활성 3 + 삭제글 1)
        persistLike(U1, LikeTargetType.POST, pA.getId(), L1);
        persistLike(U1, LikeTargetType.POST, pB.getId(), L2);
        persistLike(U1, LikeTargetType.POST, pC.getId(), L3);
        persistLike(U1, LikeTargetType.POST, pDeleted.getId(), L4); // 삭제글 → 제외
        // U1의 댓글 좋아요 — target_type=COMMENT는 게시글 목록에서 제외돼야 함(target_id는 우연히 pA.id와 같게 둬도 무관)
        persistLike(U1, LikeTargetType.COMMENT, pA.getId(), L5);
        // 다른 사용자(U2)의 좋아요 — U1 결과에 새면 안 됨
        persistLike(U2, LikeTargetType.POST, pB.getId(), L2);
        persistLike(U2, LikeTargetType.POST, pE.getId(), L3);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("UNIQUE 제약: 같은 (user, target_type, target_id) 재좋아요는 DataIntegrityViolationException")
    void unique_중복좋아요_위반() {
        likeRepository.saveAndFlush(Like.ofPost(U1, pA.getId() + 1000)); // 새 타깃이면 정상 저장(대조군)

        Like dup = Like.ofPost(U1, pA.getId()); // (U1, POST, pA) — setUp에서 이미 존재
        assertThatThrownBy(() -> likeRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("latest: U1의 좋아요한 활성 게시글만 좋아요 누른 시각 DESC — 삭제글·COMMENT·타인 좋아요 제외")
    void 관심글_latest_정렬_필터() {
        Page<LikedPostProjection> page =
                likeRepository.findLikedPostsOrderByLikedAt(U1, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(p -> p.getPost().getPublicId())
                .containsExactly(pC.getPublicId(), pB.getPublicId(), pA.getPublicId());
        assertThat(page.getTotalElements()).isEqualTo(3); // 삭제글(pDeleted)·COMMENT·pE 제외
    }

    @Test
    @DisplayName("latest: 프로젝션이 Post 본문과 liked_at(좋아요 누른 시각)을 함께 담는다")
    void 관심글_프로젝션_likedAt() {
        Page<LikedPostProjection> page =
                likeRepository.findLikedPostsOrderByLikedAt(U1, PageRequest.of(0, 20));

        LikedPostProjection first = page.getContent().get(0); // 가장 최근 좋아요 = pC(L3)
        assertThat(first.getPost().getPublicId()).isEqualTo(pC.getPublicId());
        assertThat(first.getPost().getTitle()).isEqualTo("임금체불");
        assertThat(first.getLikedAt()).isEqualTo(L3);
    }

    @Test
    @DisplayName("popular: 좋아요한 활성 게시글을 like_count DESC로 (pB=5, pC=3, pA=1)")
    void 관심글_popular_정렬() {
        Page<LikedPostProjection> page =
                likeRepository.findLikedPostsOrderByLikeCount(U1, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(p -> p.getPost().getPublicId())
                .containsExactly(pB.getPublicId(), pC.getPublicId(), pA.getPublicId());
    }

    @Test
    @DisplayName("페이지네이션: latest size=2 — page0=[pC,pB], page1=[pA], total=3/2페이지")
    void 관심글_페이지네이션() {
        Page<LikedPostProjection> page0 =
                likeRepository.findLikedPostsOrderByLikedAt(U1, PageRequest.of(0, 2));
        assertThat(page0.getContent()).extracting(p -> p.getPost().getPublicId())
                .containsExactly(pC.getPublicId(), pB.getPublicId());
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getNumber()).isZero();

        Page<LikedPostProjection> page1 =
                likeRepository.findLikedPostsOrderByLikedAt(U1, PageRequest.of(1, 2));
        assertThat(page1.getContent()).extracting(p -> p.getPost().getPublicId())
                .containsExactly(pA.getPublicId());
    }

    @Test
    @DisplayName("다른 사용자 결과 격리: U2 조회는 U2가 좋아요한 활성 글(pB,pE)만 — U1 좋아요 제외")
    void 관심글_사용자별_격리() {
        Page<LikedPostProjection> page =
                likeRepository.findLikedPostsOrderByLikeCount(U2, PageRequest.of(0, 20));

        // U2는 pB(5)·pE(9) 좋아요. popular면 pE(9), pB(5).
        assertThat(page.getContent()).extracting(p -> p.getPost().getPublicId())
                .containsExactly(pE.getPublicId(), pB.getPublicId());
    }

    @Test
    @DisplayName("existsBy/findBy: (user, POST, targetId)로 존재·단건 조회, COMMENT/타인은 별개")
    void exists_find_조회() {
        assertThat(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                U1, LikeTargetType.POST, pA.getId())).isTrue();
        assertThat(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                U2, LikeTargetType.POST, pA.getId())).isFalse(); // U2는 pA 안 누름
        assertThat(likeRepository.findByUserPublicIdAndTargetTypeAndTargetId(
                U1, LikeTargetType.POST, pC.getId())).isPresent();
        assertThat(likeRepository.findByUserPublicIdAndTargetTypeAndTargetId(
                U1, LikeTargetType.POST, pE.getId())).isEmpty(); // U1은 pE 안 누름
    }

    @Test
    @DisplayName("COM-02 원자 삭제: deleteBy가 영향행 수 반환 — 첫 호출 1(삭제), 같은 키 재호출 0(이미 없음)")
    void 원자삭제_영향행_반환() {
        // U1은 setUp에서 pC를 좋아요한 상태. 첫 삭제는 1행, 같은 키 재삭제(동시 취소에서 진 쪽)는 0행.
        int first = likeRepository.deleteByUserPublicIdAndTargetTypeAndTargetId(
                U1, LikeTargetType.POST, pC.getId());
        int second = likeRepository.deleteByUserPublicIdAndTargetTypeAndTargetId(
                U1, LikeTargetType.POST, pC.getId());

        assertThat(first).isEqualTo(1);  // 실제로 1행 삭제 → 호출 측은 이때만 like_count 감소
        assertThat(second).isZero();     // 이미 없음 → 0행(과차감 방지의 핵심: 진 쪽은 감소 안 함)

        // 키 정밀도: pC만 지워지고 U1의 다른 좋아요(pA)·타입 분리(COMMENT)는 그대로다.
        assertThat(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                U1, LikeTargetType.POST, pC.getId())).isFalse();
        assertThat(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                U1, LikeTargetType.POST, pA.getId())).isTrue();
        assertThat(likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                U1, LikeTargetType.COMMENT, pA.getId())).isTrue();
    }

    // ----- helpers -----

    /** 게시글 1건 영속화 후 native UPDATE로 like_count/deleted를 지정값으로 만든다(PostRepositoryTest 기법). */
    private Post persistPost(String userPublicId, PostCategory category, String title, String content,
                            int likeCount, boolean deleted) {
        Post p = Post.of(userPublicId, category, "ko", title, content);
        if (deleted) {
            p.softDelete();
        }
        em.persist(p);
        em.getEntityManager()
                .createNativeQuery("UPDATE posts SET like_count = ?1 WHERE id = ?2")
                .setParameter(1, likeCount)
                .setParameter(2, p.getId())
                .executeUpdate();
        return p;
    }

    /** 좋아요 1건 영속화 후 native UPDATE로 created_at(좋아요 누른 시각)을 지정값으로 박는다. */
    private void persistLike(String userPublicId, LikeTargetType targetType, Long targetId,
                            LocalDateTime createdAt) {
        Like like = (targetType == LikeTargetType.POST)
                ? Like.ofPost(userPublicId, targetId)
                : buildCommentLike(userPublicId, targetId);
        em.persist(like);
        em.getEntityManager()
                .createNativeQuery("UPDATE likes SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, like.getId())
                .executeUpdate();
    }

    /** COMMENT 좋아요는 정적 팩토리가 없어(범위 밖) 빌더 우회용 헬퍼로 만든다(필터 제외 검증 전용). */
    private Like buildCommentLike(String userPublicId, Long targetId) {
        Like like = Like.ofPost(userPublicId, targetId);
        org.springframework.test.util.ReflectionTestUtils.setField(like, "targetType", LikeTargetType.COMMENT);
        return like;
    }
}