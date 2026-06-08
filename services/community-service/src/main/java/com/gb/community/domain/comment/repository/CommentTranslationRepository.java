package com.gb.community.domain.comment.repository;

import com.gb.community.domain.comment.entity.CommentTranslation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link CommentTranslation} Repository — 댓글 번역 캐시 조회·삭제.
 * {@code PostTranslationRepository}와 동일 패턴 (댓글은 제목 없음).
 */
public interface CommentTranslationRepository
        extends JpaRepository<CommentTranslation, CommentTranslation.CommentTranslationId> {

    /** 댓글의 특정 언어 번역 캐시 조회 (PK 인덱스). */
    Optional<CommentTranslation> findByCommentIdAndLanguage(Long commentId, String language);

    /** 댓글 ID로 모든 언어 번역 캐시 일괄 삭제 (본문 수정 시 무효화). */
    @Modifying
    @Query("DELETE FROM CommentTranslation ct WHERE ct.comment.id = :commentId")
    void deleteByCommentId(@Param("commentId") Long commentId);
}
