package com.gb.appadmin.domain.feePolicy.service;

import com.gb.appadmin.domain.feePolicy.dto.request.FeePolicyCreateRequest;
import com.gb.appadmin.domain.feePolicy.dto.request.FeePolicyUpdateRequest;
import com.gb.appadmin.domain.feePolicy.dto.response.FeePolicyResponse;
import java.util.List;

public interface FeePolicyService {
    List<FeePolicyResponse> listAll();
    FeePolicyResponse getByPublicId(String publicId);
    FeePolicyResponse create(FeePolicyCreateRequest request);
    FeePolicyResponse update(String publicId, String adminPublicId, FeePolicyUpdateRequest request);
}
