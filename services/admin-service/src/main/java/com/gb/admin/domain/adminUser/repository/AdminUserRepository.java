package com.gb.admin.domain.adminUser.repository;

import com.gb.admin.domain.adminUser.entity.AdminUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByPublicId(String publicId);

    Optional<AdminUser> findByEmail(String email);
}
