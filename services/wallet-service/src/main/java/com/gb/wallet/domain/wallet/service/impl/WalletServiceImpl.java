package com.gb.wallet.domain.wallet.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.wallet.dto.response.WalletBalanceResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletMeResponse;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.domain.wallet.service.WalletService;
import com.gb.wallet.global.client.ExchangeRateClient;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final ExchangeRateClient exchangeRateClient;

    @Override
    public WalletBalanceResponse getMyBalances(String userPublicId) {
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        List<WalletBalance> balances = walletBalanceRepository.findByWallet(wallet);

        return WalletBalanceResponse.from(wallet, balances);
    }

    @Override
    public WalletMeResponse getMyWallet(String userPublicId) {
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        List<WalletBalance> balances = walletBalanceRepository.findByWallet(wallet);

        // 잔액에 등장하는 모든 통화에 대해 "1 외화→KRW" 환율 조회.
        // 미지원 통화(환율 null) 발생 시 TRANSFER4002 로 거부 (안전망: 응답에 미지원 통화가 섞이지 않게).
        Map<CurrencyType, BigDecimal> ratesToKrw = new EnumMap<>(CurrencyType.class);
        for (WalletBalance b : balances) {
            CurrencyType currency = b.getCurrencyCode();
            if (ratesToKrw.containsKey(currency)) {
                continue; // 같은 통화 중복 조회 회피
            }
            BigDecimal rate = exchangeRateClient.getRateToKrw(currency);
            if (rate == null) {
                throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY);
            }
            ratesToKrw.put(currency, rate);
        }

        return WalletMeResponse.from(wallet, balances, ratesToKrw);
    }
}
