package com.gb.community.domain.report.repository;

import com.gb.community.domain.report.entity.Report;
import com.gb.community.domain.report.entity.ReportReason;
import com.gb.community.domain.report.entity.ReportStatus;
import com.gb.community.domain.report.entity.ReportTargetType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /**
     * 중복 신고 확인 — 동일 (reporter, target_type, target_id) 조합이 이미 존재하는지.
     * DB UniqueConstraint와 이중 방어선(CLAUDE.md §6: 비즈니스 예외는 서비스에서 throw).
     */
    boolean existsByReporterPublicIdAndTargetTypeAndTargetId(
            String reporterPublicId, ReportTargetType targetType, Long targetId);

    /**
     * 관리자 신고 목록 조회 (필터: status, reason). null 파라미터는 전체 조회.
     * 서비스에서 Java-level grouping으로 (targetType, targetId) 집계에 사용.
     */
    @Query("""
            SELECT r FROM Report r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:reason IS NULL OR r.reason = :reason)
            ORDER BY r.createdAt DESC
            """)
    List<Report> findByStatusAndReason(
            @Param("status") ReportStatus status,
            @Param("reason") ReportReason reason);

    /**
     * 특정 (targetType, targetId) 목록에 해당하는 신고 배치 조회.
     * by-author 집계 시 N+1 방지를 위해 IN 절로 한 번에 가져온다.
     */
    @Query("""
            SELECT r FROM Report r
            WHERE r.targetType = :targetType AND r.targetId IN :targetIds
            ORDER BY r.createdAt DESC
            """)
    List<Report> findByTargetTypeAndTargetIdIn(
            @Param("targetType") ReportTargetType targetType,
            @Param("targetIds") List<Long> targetIds);

    /**
     * 특정 (targetType, targetId)의 신고 단건 목록 — by-author 상세 조회용.
     */
    List<Report> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            ReportTargetType targetType, Long targetId);

    /**
     * 특정 대상의 모든 신고 상태 일괄 변경 (관리자 삭제/무효 처리 시).
     * @Modifying + @Transactional은 호출 Service 계층에서 보장.
     */
    @Modifying
    @Query("""
            UPDATE Report r
            SET r.status = :status
            WHERE r.targetType = :targetType AND r.targetId = :targetId
            """)
    void updateStatusByTarget(
            @Param("targetType") ReportTargetType targetType,
            @Param("targetId") Long targetId,
            @Param("status") ReportStatus status);

    /** 신고 통계 — 특정 상태의 신고 수. */
    long countByStatus(ReportStatus status);

    /** 전체 신고 수 (상태 무관). */
    long countByTargetType(ReportTargetType targetType);
}
