package com.gb.appadmin.domain.exchangeRatePolicy.service;

import com.gb.appadmin.domain.exchangeRatePolicy.dto.request.ExchangeRatePolicyCreateRequest;
import com.gb.appadmin.domain.exchangeRatePolicy.dto.request.ExchangeRatePolicyUpdateRequest;
import com.gb.appadmin.domain.exchangeRatePolicy.dto.response.ExchangeRatePolicyResponse;
import java.util.List;

public interface ExchangeRatePolicyService {
    List<ExchangeRatePolicyResponse> listAll();
    ExchangeRatePolicyResponse getByPublicId(String publicId);
    ExchangeRatePolicyResponse create(ExchangeRatePolicyCreateRequest request);
    ExchangeRatePolicyResponse update(String publicId, String adminPublicId, ExchangeRatePolicyUpdateRequest request);
}
