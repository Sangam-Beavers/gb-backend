package com.gb.appadmin.domain.serviceSetting.repository;

import com.gb.appadmin.domain.serviceSetting.entity.ServiceSetting;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceSettingRepository extends JpaRepository<ServiceSetting, Long> {
    Optional<ServiceSetting> findByPublicId(String publicId);
    Optional<ServiceSetting> findBySettingKey(String settingKey);
    List<ServiceSetting> findAllByOrderBySettingKeyAsc();
}
