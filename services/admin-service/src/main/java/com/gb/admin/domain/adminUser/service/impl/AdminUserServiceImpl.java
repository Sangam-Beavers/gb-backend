package com.gb.admin.domain.adminUser.service.impl;

import com.gb.admin.domain.adminUser.dto.response.AdminUserResponse;
import com.gb.admin.domain.adminUser.entity.AdminRole;
import com.gb.admin.domain.adminUser.entity.AdminUser;
import com.gb.admin.domain.adminUser.repository.AdminUserRepository;
import com.gb.admin.domain.adminUser.repository.AdminUserRoleRepository;
import com.gb.admin.domain.adminUser.service.AdminUserService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserServiceImpl implements AdminUserService {

    private final AdminUserRepository adminUserRepository;
    private final AdminUserRoleRepository adminUserRoleRepository;

    @Override
    public AdminUserResponse getMe(String adminPublicId) {
        Optional<AdminUser> found = adminUserRepository.findByPublicId(adminPublicId);
        if (found.isEmpty()) {
            // Phase 1 — IdP 세팅 전이라 admin DB seed와 토큰 claim이 어긋날 수 있어 mock SUPER로 안내.
            // 다음 스프린트에서 ADMIN4001(존재하지 않는 관리자)로 fail-fast 전환 예정.
            return AdminUserResponse.mockSuperAdmin(adminPublicId);
        }
        AdminUser entity = found.get();
        List<AdminRole> roles = adminUserRoleRepository.findByAdminUserId(entity.getId()).stream()
                .map(r -> r.getRole())
                .toList();
        return AdminUserResponse.from(entity, roles);
    }
}
