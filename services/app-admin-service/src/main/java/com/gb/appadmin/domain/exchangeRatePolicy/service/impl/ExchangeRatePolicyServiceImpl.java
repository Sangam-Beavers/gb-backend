package com.gb.appadmin.domain.exchangeRatePolicy.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.appadmin.domain.exchangeRatePolicy.dto.request.ExchangeRatePolicyCreateRequest;
import com.gb.appadmin.domain.exchangeRatePolicy.dto.request.ExchangeRatePolicyUpdateRequest;
import com.gb.appadmin.domain.exchangeRatePolicy.dto.response.ExchangeRatePolicyResponse;
import com.gb.appadmin.domain.exchangeRatePolicy.entity.ExchangeRatePolicy;
import com.gb.appadmin.domain.exchangeRatePolicy.entity.ExchangeRatePolicyHistory;
import com.gb.appadmin.domain.exchangeRatePolicy.repository.ExchangeRatePolicyHistoryRepository;
import com.gb.appadmin.domain.exchangeRatePolicy.repository.ExchangeRatePolicyRepository;
import com.gb.appadmin.domain.exchangeRatePolicy.service.ExchangeRatePolicyService;
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
public class ExchangeRatePolicyServiceImpl implements ExchangeRatePolicyService {

    private final ExchangeRatePolicyRepository policyRepository;
    private final ExchangeRatePolicyHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Override
    public List<ExchangeRatePolicyResponse> listAll() {
        return policyRepository.findAllByOrderByCurrencyCodeAsc()
                .stream().map(ExchangeRatePolicyResponse::from).toList();
    }

    @Override
    public ExchangeRatePolicyResponse getByPublicId(String publicId) {
        return ExchangeRatePolicyResponse.from(findOrThrow(publicId));
    }

    @Override
    @Transactional
    public ExchangeRatePolicyResponse create(ExchangeRatePolicyCreateRequest request) {
        ExchangeRatePolicy policy = ExchangeRatePolicy.builder()
                .publicId(UUID.randomUUID().toString())
                .currencyCode(request.currencyCode())
                .spread(request.spread())
                .active(request.active())
                .build();
        return ExchangeRatePolicyResponse.from(policyRepository.save(policy));
    }

    @Override
    @Transactional
    public ExchangeRatePolicyResponse update(String publicId, String adminPublicId,
                                             ExchangeRatePolicyUpdateRequest request) {
        ExchangeRatePolicy policy = findOrThrow(publicId);
        String before = toJson(ExchangeRatePolicyResponse.from(policy));
        policy.update(request.spread(), request.active());
        String after = toJson(ExchangeRatePolicyResponse.from(policy));
        historyRepository.save(ExchangeRatePolicyHistory.builder()
                .exchangeRatePolicy(policy)
                .changedByAdminPublicId(adminPublicId)
                .beforeSnapshot(before)
                .afterSnapshot(after)
                .build());
        return ExchangeRatePolicyResponse.from(policy);
    }

    private ExchangeRatePolicy findOrThrow(String publicId) {
        return policyRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(AppAdminErrorCode.EXCHANGE_RATE_POLICY_NOT_FOUND));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("ExchangeRatePolicyHistory snapshot 직렬화 실패: {}", e.getMessage());
            return "{}";
        }
    }
}
