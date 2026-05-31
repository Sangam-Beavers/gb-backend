package com.gb.wallet.domain.exchange.service;

import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;

public interface ExchangeService {

    /** 환전·재환전에서 사용 가능한 지원 통화 목록을 반환한다. */
    SupportedCurrenciesResponse getSupportedCurrencies();
}
