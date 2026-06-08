package com.gb.admin.domain.adminUser.controller;

import com.gb.admin.domain.adminUser.dto.response.AdminUserResponse;
import com.gb.admin.domain.adminUser.service.AdminUserService;
import com.gb.admin.global.security.CurrentAdminPublicId;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Users", description = "관리자 사용자 정보")
@RestController
@RequestMapping("/api/v1/admin/admins")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "내 관리자 정보 조회",
            description = "JWT의 public_id로 admin DB를 조회한다. Phase 1은 DB 미스 시 mock(admin01 SUPER)을 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    public ApiResponse<AdminUserResponse> getMe(@CurrentAdminPublicId String adminPublicId) {
        return ApiResponse.success(adminUserService.getMe(adminPublicId));
    }
}
