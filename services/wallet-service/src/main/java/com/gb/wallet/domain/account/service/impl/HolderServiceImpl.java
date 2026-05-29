package com.gb.wallet.domain.account.service.impl;

import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.account.service.HolderService;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link HolderService} 구현.
 * DB 호출이 없는 외부 API 어댑터지만, 다른 서비스와 일관성을 위해 {@code readOnly} 트랜잭션 경계를 유지한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HolderServiceImpl implements HolderService {

    private final BankClient bankClient;

    @Override
    public AccountHolderResponse getAccountHolder(String bankCode, String accountNumber) {
        AccountHolder holder = bankClient.inquiry(bankCode, accountNumber);
        return AccountHolderResponse.from(holder);
    }
}
