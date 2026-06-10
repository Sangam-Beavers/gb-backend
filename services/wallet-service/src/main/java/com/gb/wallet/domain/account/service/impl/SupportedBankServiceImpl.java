package com.gb.wallet.domain.account.service.impl;

import com.gb.wallet.domain.account.dto.response.SupportedBankListResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.repository.BankRepository;
import com.gb.wallet.domain.account.service.SupportedBankService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupportedBankServiceImpl implements SupportedBankService {

    private final BankRepository bankRepository;

    @Override
    public SupportedBankListResponse getSupportedBanks() {
        List<Bank> banks = bankRepository.findAllByIsActiveTrueOrderByCountryAscNameAsc();
        return SupportedBankListResponse.from(banks);
    }
}
