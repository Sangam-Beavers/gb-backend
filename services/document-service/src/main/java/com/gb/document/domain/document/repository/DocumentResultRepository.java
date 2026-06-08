package com.gb.document.domain.document.repository;

import com.gb.document.domain.document.entity.DocumentResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * DocumentResult Repository. 분석 결과 상세 조회와 목록 join용.
 */
public interface DocumentResultRepository extends JpaRepository<DocumentResult, Long> {

    /** 결과 상세 조회 — submission.public_id로 검색. */
    Optional<DocumentResult> findBySubmission_PublicId(String publicId);

    /**
     * Consumer 멱등성 처리용 — submission_id UNIQUE이라 같은 submission의 결과가 1건만 존재.
     * 동일 메시지 재수신(at-least-once) 또는 retry 후 성공 시 UPDATE 분기 진입에 사용.
     */
    Optional<DocumentResult> findBySubmission_Id(Long submissionId);

    /** 목록 화면에서 risk_level 표시용. submission id IN (...) batch 조회. */
    List<DocumentResult> findAllBySubmission_IdIn(List<Long> submissionIds);

    // ===== Admin internal API =====

    /** 기간 내 risk_level 별 카운트(stats). */
    @org.springframework.data.jpa.repository.Query("""
            SELECT r.overallRiskLevel AS risk, COUNT(r) AS cnt
            FROM DocumentResult r
            WHERE r.submission.createdAt BETWEEN :from AND :to
            GROUP BY r.overallRiskLevel
            """)
    List<RiskLevelCountProjection> countByRiskLevelBetween(
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    /** 기간 내 processing_status 별 카운트(stats). */
    @org.springframework.data.jpa.repository.Query("""
            SELECT r.processingStatus AS status, COUNT(r) AS cnt
            FROM DocumentResult r
            WHERE r.submission.createdAt BETWEEN :from AND :to
            GROUP BY r.processingStatus
            """)
    List<ProcessingStatusCountProjection> countByProcessingStatusBetween(
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    interface RiskLevelCountProjection {
        com.gb.document.domain.document.entity.RiskLevel getRisk();
        long getCnt();
    }

    interface ProcessingStatusCountProjection {
        com.gb.document.domain.document.entity.ProcessingStatus getStatus();
        long getCnt();
    }
}
