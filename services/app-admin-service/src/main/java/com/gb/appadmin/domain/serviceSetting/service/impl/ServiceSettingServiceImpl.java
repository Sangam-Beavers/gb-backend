package com.gb.appadmin.domain.serviceSetting.service.impl;

import com.gb.appadmin.domain.serviceSetting.dto.request.ServiceSettingCreateRequest;
import com.gb.appadmin.domain.serviceSetting.dto.request.ServiceSettingUpdateRequest;
import com.gb.appadmin.domain.serviceSetting.dto.response.ServiceSettingResponse;
import com.gb.appadmin.domain.serviceSetting.entity.ServiceSetting;
import com.gb.appadmin.domain.serviceSetting.repository.ServiceSettingRepository;
import com.gb.appadmin.domain.serviceSetting.service.ServiceSettingService;
import com.gb.appadmin.global.exception.code.AppAdminErrorCode;
import com.gb.common.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceSettingServiceImpl implements ServiceSettingService {

    private final ServiceSettingRepository settingRepository;

    @Override
    public List<ServiceSettingResponse> listAll() {
        return settingRepository.findAllByOrderBySettingKeyAsc()
                .stream().map(ServiceSettingResponse::from).toList();
    }

    @Override
    public ServiceSettingResponse getByPublicId(String publicId) {
        return ServiceSettingResponse.from(findOrThrow(publicId));
    }

    @Override
    public ServiceSettingResponse getBySettingKey(String settingKey) {
        return ServiceSettingResponse.from(
                settingRepository.findBySettingKey(settingKey)
                        .orElseThrow(() -> new BusinessException(AppAdminErrorCode.SERVICE_SETTING_NOT_FOUND))
        );
    }

    @Override
    @Transactional
    public ServiceSettingResponse create(ServiceSettingCreateRequest request) {
        ServiceSetting setting = ServiceSetting.builder()
                .publicId(UUID.randomUUID().toString())
                .settingKey(request.settingKey())
                .settingValue(request.settingValue())
                .description(request.description())
                .active(request.active())
                .build();
        return ServiceSettingResponse.from(settingRepository.save(setting));
    }

    @Override
    @Transactional
    public ServiceSettingResponse update(String publicId, ServiceSettingUpdateRequest request) {
        ServiceSetting setting = findOrThrow(publicId);
        setting.update(request.settingValue(), request.description(), request.active());
        return ServiceSettingResponse.from(setting);
    }

    private ServiceSetting findOrThrow(String publicId) {
        return settingRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(AppAdminErrorCode.SERVICE_SETTING_NOT_FOUND));
    }
}
