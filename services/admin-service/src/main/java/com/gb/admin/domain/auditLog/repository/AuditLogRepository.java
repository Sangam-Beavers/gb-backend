package com.gb.admin.domain.auditLog.repository;

import com.gb.admin.domain.auditLog.entity.AuditLog;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 감사 로그 검색 — 필터(action/target_type/from/to) 전부 nullable. JPQL CASE 대신
     * {@code IS NULL OR =} 조건으로 동적 필터를 표현한다(QueryDSL 미도입 — Phase 1 단순화).
     */
    @Query("""
            SELECT a FROM AuditLog a
             WHERE (:action IS NULL OR a.action = :action)
               AND (:targetType IS NULL OR a.targetType = :targetType)
               AND (:from IS NULL OR a.createdAt >= :from)
               AND (:to IS NULL OR a.createdAt <= :to)
            """)
    Page<AuditLog> search(@Param("action") String action,
                          @Param("targetType") String targetType,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          Pageable pageable);
}
