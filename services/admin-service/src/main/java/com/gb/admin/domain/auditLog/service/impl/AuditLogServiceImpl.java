package com.gb.admin.domain.auditLog.service.impl;

import com.gb.admin.domain.auditLog.dto.response.AuditLogPageResponse;
import com.gb.admin.domain.auditLog.dto.response.AuditLogResponse;
import com.gb.admin.domain.auditLog.entity.AuditLog;
import com.gb.admin.domain.auditLog.repository.AuditLogRepository;
import com.gb.admin.domain.auditLog.service.AuditLogService;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public void record(String adminPublicId, String action, String targetType, String targetPublicId,
                       String ipAddress, String beforeJson, String afterJson) {
        AuditLog entity = AuditLog.builder()
                .publicId(UUID.randomUUID().toString())
                .adminPublicId(adminPublicId)
                .action(action)
                .targetType(targetType)
                .targetPublicId(targetPublicId)
                .ipAddress(ipAddress)
                .beforeSnapshot(beforeJson)
                .afterSnapshot(afterJson)
                .build();
        auditLogRepository.save(entity);
    }

    @Override
    public AuditLogPageResponse search(String action, String targetType, LocalDateTime from, LocalDateTime to,
                                       int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> result = auditLogRepository.search(action, targetType, from, to, pageable);
        return AuditLogPageResponse.from(result.map(AuditLogResponse::from));
    }
}
