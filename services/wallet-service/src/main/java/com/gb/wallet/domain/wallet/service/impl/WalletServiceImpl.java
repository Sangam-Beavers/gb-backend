package com.gb.wallet.domain.wallet.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.wallet.dto.response.WalletBalanceResponse;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.domain.wallet.service.WalletService;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;

    @Override
    public WalletBalanceResponse getMyBalances(String userPublicId) {
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        List<WalletBalance> balances = walletBalanceRepository.findByWallet(wallet);

        return WalletBalanceResponse.from(wallet, balances);
    }
}
