package com.gb.admin.domain.adminUser.repository;

import com.gb.admin.domain.adminUser.entity.AdminUserRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRoleRepository extends JpaRepository<AdminUserRole, Long> {

    List<AdminUserRole> findByAdminUserId(Long adminUserId);
}
