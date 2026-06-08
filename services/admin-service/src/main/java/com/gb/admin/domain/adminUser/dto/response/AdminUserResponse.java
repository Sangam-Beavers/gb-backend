package com.gb.admin.domain.adminUser.dto.response;

import com.gb.admin.domain.adminUser.entity.AdminRole;
import com.gb.admin.domain.adminUser.entity.AdminUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "관리자 본인 정보 응답")
public record AdminUserResponse(
        @Schema(description = "관리자 public_id(UUID).", example = "00000000-0000-0000-0000-000000000001")
        String publicId,

        @Schema(description = "관리자 이메일.", example = "admin01@gb.com")
        String email,

        @Schema(description = "관리자 닉네임/표시 이름.", example = "Admin Kim")
        String nickname,

        @Schema(description = "활성 여부.", example = "true")
        boolean isActive,

        @Schema(description = "보유 역할 목록.", example = "[\"SUPER\"]")
        List<AdminRole> roles
) {

    public static AdminUserResponse from(AdminUser entity, List<AdminRole> roles) {
        return new AdminUserResponse(
                entity.getPublicId(),
                entity.getEmail(),
                entity.getNickname(),
                entity.isActive(),
                roles
        );
    }

    /** Phase 1 mock 응답 — JWT public_id로 admin DB에 매칭되지 않을 때 admin01 fixture 반환용. */
    public static AdminUserResponse mockSuperAdmin(String publicId) {
        return new AdminUserResponse(
                publicId,
                "admin01@gb.com",
                "Admin Kim",
                true,
                List.of(AdminRole.SUPER)
        );
    }
}
