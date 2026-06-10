package com.gb.appadmin.domain.serviceSetting.entity;

import com.gb.appadmin.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 앱 서비스 전반 설정(키-값).
 * setting_key는 코드에서 관리되는 고정값이라 UI에서 신규 key 생성은 지원하지 않는다.
 * 값 변경(update)만 허용한다.
 */
@Entity
@Getter
@Table(name = "service_settings",
        indexes = {
                @Index(name = "idx_service_settings_key", columnList = "setting_key", unique = true),
                @Index(name = "idx_service_settings_active", columnList = "active")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    /** 설정 키 (예: MAINTENANCE_MODE, MAX_TRANSFER_AMOUNT) */
    @Column(name = "setting_key", length = 100, nullable = false, unique = true)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "TEXT", nullable = false)
    private String settingValue;

    /** 설정 목적 설명 */
    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Builder
    private ServiceSetting(String publicId, String settingKey, String settingValue,
                           String description, Boolean active) {
        this.publicId = publicId;
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.description = description;
        this.active = active != null ? active : true;
    }

    public void update(String settingValue, String description, Boolean active) {
        if (settingValue != null) this.settingValue = settingValue;
        if (description != null) this.description = description;
        if (active != null) this.active = active;
    }
}
