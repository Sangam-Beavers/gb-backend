package com.gb.document.domain.document.repository;

import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Document 엔티티 Repository.
 *
 * <p><b>공유 인터페이스</b> — AI-WORK-SPLIT.md §3-③.
 * 챗봇 ChatController(심규보)가 권한 검증을 위해 {@link #findByPublicId(String)} 한 메서드만 사용한다.
 * 유진이 새 메서드를 추가하는 건 자유지만, <b>이 메서드의 시그니처는 변경 금지</b>.
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 외부 노출 식별자(public_id)로 문서를 찾는다. 없으면 빈 Optional.
     * ChatController가 호출 후 {@code DocumentErrorCode.DOCUMENT_NOT_FOUND}로 던진다.
     */
    Optional<Document> findByPublicId(String publicId);

    /**
     * 특정 사용자의 분석 요청 목록을 페이지로 조회한다. 정렬은 호출 측 {@link Pageable}에서 지정.
     * (보통 createdAt DESC — 최신 업로드부터.)
     */
    Page<Document> findAllByUserPublicId(String userPublicId, Pageable pageable);

    /**
     * 특정 사용자의 분석 요청 목록을 상태 필터와 함께 페이지로 조회한다.
     * 목록 API의 {@code ?status=} 필터용 — 프론트가 실패(FAILED) 내역을 숨길 때 사용.
     */
    Page<Document> findAllByUserPublicIdAndStatusIn(
            String userPublicId, Collection<DocumentStatus> statuses, Pageable pageable);

    /**
     * 특정 상태로 기준 시각보다 오래 머문 문서를 찾는다 — 미업로드/결과 유실 건 FAILED 정리 스케줄러용
     * ({@code StaleSubmissionSweeper}). updatedAt 기준이라 retry로 ANALYZING 복귀 시 유예가 다시 시작된다.
     */
    List<Document> findAllByStatusAndUpdatedAtBefore(DocumentStatus status, LocalDateTime threshold);

    // ===== Admin internal API =====

    /** 관리자용 페이지 조회: 전체 또는 user_public_id 옵션 필터. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT d FROM Document d
            WHERE (:userPublicId IS NULL OR d.userPublicId = :userPublicId)
            """)
    org.springframework.data.domain.Page<Document> searchForAdmin(
            @org.springframework.data.repository.query.Param("userPublicId") String userPublicId,
            org.springframework.data.domain.Pageable pageable);

    /** 기간 내 문서 카운트(stats용). */
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    long countByStatusAndCreatedAtBetween(DocumentStatus status, LocalDateTime from, LocalDateTime to);
}
