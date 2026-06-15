package com.gb.appadmin.domain.serviceSetting.service;

import com.gb.appadmin.domain.serviceSetting.dto.request.ServiceSettingCreateRequest;
import com.gb.appadmin.domain.serviceSetting.dto.request.ServiceSettingUpdateRequest;
import com.gb.appadmin.domain.serviceSetting.dto.response.ServiceSettingResponse;
import java.util.List;

public interface ServiceSettingService {
    List<ServiceSettingResponse> listAll();
    ServiceSettingResponse getByPublicId(String publicId);
    ServiceSettingResponse getBySettingKey(String settingKey);
    ServiceSettingResponse create(ServiceSettingCreateRequest request);
    ServiceSettingResponse update(String publicId, ServiceSettingUpdateRequest request);
}
