package com.gb.wallet.domain.account.service;

import com.gb.wallet.domain.account.dto.response.SupportedBankListResponse;

public interface SupportedBankService {

    /** 추가 지원 은행 목록(활성 국내 은행, 가나다순)을 조회한다. */
    SupportedBankListResponse getSupportedBanks();
}
