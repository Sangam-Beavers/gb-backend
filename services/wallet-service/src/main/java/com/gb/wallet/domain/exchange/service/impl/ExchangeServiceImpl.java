package com.gb.wallet.domain.exchange.service.impl;

import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.exchange.service.ExchangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExchangeServiceImpl implements ExchangeService {

    @Override
    @Transactional(readOnly = true)
    public SupportedCurrenciesResponse getSupportedCurrencies() {
        // 지원 통화는 CurrencyType enum이 SSOT — DB 조회 없이 enum에서 구성한다.
        return SupportedCurrenciesResponse.of();
    }
}
