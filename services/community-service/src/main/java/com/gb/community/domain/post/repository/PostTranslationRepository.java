package com.gb.community.domain.post.repository;

import com.gb.community.domain.post.entity.PostTranslation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link PostTranslation} Repository — 게시글 번역 캐시 조회·삭제.
 *
 * <p>복합 PK {@code (post_id, language)}는 {@link PostTranslation.PostTranslationId}로 표현된다.
 * 메서드 네이밍 {@code findByPostIdAndLanguage}는 Spring Data JPA가 연관 매핑 {@code post}의 PK 필드
 * {@code id}로 풀어준다 — 같은 의미인 {@code findByPost_IdAndLanguage}도 가능하지만, 본 프로젝트는
 * 언더스코어 없는 형태를 채택해 호출 측이 깔끔하다.
 *
 * <p>{@code deleteByPostId}는 게시글 본문/제목 수정 시 해당 글의 <b>모든 언어</b> 번역 캐시를 일괄
 * 제거하기 위한 벌크 DELETE다 — Service가 {@code updatePostTx} 내에서 명시 호출(DB CASCADE 미사용).
 * 캐시 무효화는 도메인 정책이라 엔티티 cascade에 숨기지 않고 코드 흐름에서 보이게 한다.
 */
public interface PostTranslationRepository
        extends JpaRepository<PostTranslation, PostTranslation.PostTranslationId> {

    /**
     * 게시글의 특정 언어 번역 캐시 조회. 캐시 hit/miss 판정용. PK 인덱스를 그대로 탄다.
     */
    Optional<PostTranslation> findByPostIdAndLanguage(Long postId, String language);

    /**
     * 게시글 ID로 모든 언어 번역 캐시 일괄 삭제 (본문/제목 수정 시 무효화).
     *
     * <p>{@code @Modifying} 벌크 DELETE — 1차 캐시의 PostTranslation 인스턴스는 갱신되지 않지만
     * 호출 직후 트랜잭션이 곧 커밋되고 캐시 인스턴스도 더 이상 참조되지 않으므로 stale 위험 없음.
     */
    @Modifying
    @Query("DELETE FROM PostTranslation pt WHERE pt.post.id = :postId")
    void deleteByPostId(@Param("postId") Long postId);
}
