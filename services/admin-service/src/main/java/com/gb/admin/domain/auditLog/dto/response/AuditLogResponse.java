package com.gb.admin.domain.auditLog.dto.response;

import com.gb.admin.domain.auditLog.entity.AuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "관리자 감사 로그 응답")
public record AuditLogResponse(
        @Schema(description = "감사 로그 public_id(UUID).", example = "a1b2c3d4-1111-2222-3333-444455556666")
        String publicId,

        @Schema(description = "행위 주체 관리자 public_id.", example = "00000000-0000-0000-0000-000000000001")
        String adminPublicId,

        @Schema(description = "행위 구분.", example = "KYC_APPROVE")
        String action,

        @Schema(description = "대상 리소스 타입.", example = "MEMBER")
        String targetType,

        @Schema(description = "대상 리소스 public_id.", example = "11111111-1111-1111-1111-111111111111")
        String targetPublicId,

        @Schema(description = "요청 IP.", example = "10.10.1.50")
        String ipAddress,

        @Schema(description = "행위 직전 스냅샷(JSON).", example = "{\"kyc_status\":\"PENDING\"}")
        String beforeSnapshot,

        @Schema(description = "행위 직후 스냅샷(JSON).", example = "{\"kyc_status\":\"APPROVED\"}")
        String afterSnapshot,

        @Schema(description = "발생 시각(UTC).", example = "2026-06-07T10:21:00Z")
        LocalDateTime createdAt
) {

    public static AuditLogResponse from(AuditLog entity) {
        return new AuditLogResponse(
                entity.getPublicId(),
                entity.getAdminPublicId(),
                entity.getAction(),
                entity.getTargetType(),
                entity.getTargetPublicId(),
                entity.getIpAddress(),
                entity.getBeforeSnapshot(),
                entity.getAfterSnapshot(),
                entity.getCreatedAt()
        );
    }
}
