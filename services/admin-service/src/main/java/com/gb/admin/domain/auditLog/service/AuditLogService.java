package com.gb.admin.domain.auditLog.service;

import com.gb.admin.domain.auditLog.dto.response.AuditLogPageResponse;
import java.time.LocalDateTime;

public interface AuditLogService {

    /**
     * 감사 로그를 1건 기록(append-only). 도메인 운영 컨트롤러(KYC 승인/거절·게시글 숨김 등)에서 호출한다.
     * Phase 1은 동기 INSERT; AOP/비동기 처리는 다음 스프린트.
     */
    void record(String adminPublicId, String action, String targetType, String targetPublicId,
                String ipAddress, String beforeJson, String afterJson);

    AuditLogPageResponse search(String action, String targetType, LocalDateTime from, LocalDateTime to,
                                int page, int size);
}
