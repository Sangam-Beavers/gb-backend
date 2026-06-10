package com.gb.appadmin.domain.feePolicy.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.appadmin.domain.feePolicy.dto.request.FeePolicyCreateRequest;
import com.gb.appadmin.domain.feePolicy.dto.request.FeePolicyUpdateRequest;
import com.gb.appadmin.domain.feePolicy.dto.response.FeePolicyResponse;
import com.gb.appadmin.domain.feePolicy.entity.FeePolicy;
import com.gb.appadmin.domain.feePolicy.entity.FeePolicyHistory;
import com.gb.appadmin.domain.feePolicy.repository.FeePolicyHistoryRepository;
import com.gb.appadmin.domain.feePolicy.repository.FeePolicyRepository;
import com.gb.appadmin.domain.feePolicy.service.FeePolicyService;
import com.gb.appadmin.global.exception.code.AppAdminErrorCode;
import com.gb.common.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeePolicyServiceImpl implements FeePolicyService {

    private final FeePolicyRepository feePolicyRepository;
    private final FeePolicyHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Override
    public List<FeePolicyResponse> listAll() {
        return feePolicyRepository.findAllByOrderByServiceTypeAsc()
                .stream().map(FeePolicyResponse::from).toList();
    }

    @Override
    public FeePolicyResponse getByPublicId(String publicId) {
        return FeePolicyResponse.from(findOrThrow(publicId));
    }

    @Override
    @Transactional
    public FeePolicyResponse create(FeePolicyCreateRequest request) {
        FeePolicy policy = FeePolicy.builder()
                .publicId(UUID.randomUUID().toString())
                .serviceType(request.serviceType())
                .feeType(request.feeType())
                .feeValue(request.feeValue())
                .minFee(request.minFee())
                .maxFee(request.maxFee())
                .currency(request.currency())
                .active(request.active())
                .build();
        return FeePolicyResponse.from(feePolicyRepository.save(policy));
    }

    @Override
    @Transactional
    public FeePolicyResponse update(String publicId, String adminPublicId, FeePolicyUpdateRequest request) {
        FeePolicy policy = findOrThrow(publicId);
        String before = toJson(FeePolicyResponse.from(policy));
        policy.update(request.feeType(), request.feeValue(), request.minFee(), request.maxFee(), request.active());
        String after = toJson(FeePolicyResponse.from(policy));
        historyRepository.save(FeePolicyHistory.builder()
                .feePolicy(policy)
                .changedByAdminPublicId(adminPublicId)
                .beforeSnapshot(before)
                .afterSnapshot(after)
                .build());
        return FeePolicyResponse.from(policy);
    }

    private FeePolicy findOrThrow(String publicId) {
        return feePolicyRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(AppAdminErrorCode.FEE_POLICY_NOT_FOUND));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("FeePolicyHistory snapshot 직렬화 실패: {}", e.getMessage());
            return "{}";
        }
    }
}
