package com.gb.wallet.domain.wallet.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.wallet.dto.response.ExchangeRateWidgetResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletBalanceResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletMeResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletResponse;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final ExchangeRateClient exchangeRateClient;

    /**
     * 사용자당 1개의 지갑을 보장(멱등). 신분증 인증 APPROVED 시 member-service가 호출한다(이슈 #152).
     *
     * <p>흐름: ① 기존 지갑이 있으면 그대로 반환 → ② 없으면 {@code wallets} INSERT +
     * {@code wallet_balances(KRW, 0)} 1행을 같은 트랜잭션으로 작성. 동시에 두 번 호출되면 한 쪽이
     * {@code user_public_id UNIQUE} 위반으로 떨어지는데, 이를 catch하고 재조회로 흡수해 멱등을 지킨다.
     *
     * <p>KRW 외 통화 잔액은 첫 환전 시 lazy 생성한다(기존 환전 로직과 정합).
     */
    @Override
    @Transactional
    public WalletResponse createOrGetWallet(String userPublicId) {
        // 1) 이미 있으면 그대로 반환 (멱등).
        return walletRepository.findByUserPublicId(userPublicId)
                .map(WalletResponse::from)
                .orElseGet(() -> WalletResponse.from(createNewWalletWithKrwBalance(userPublicId)));
    }

    /**
     * 신규 지갑 + KRW 0원 잔액 행을 같은 트랜잭션에서 작성한다. 동시 호출로 인한
     * {@code wallets.user_public_id UNIQUE} 위반은 잡아서 재조회로 흡수한다(race window 멱등).
     */
    private Wallet createNewWalletWithKrwBalance(String userPublicId) {
        try {
            Wallet wallet = walletRepository.saveAndFlush(Wallet.create(userPublicId));
            walletBalanceRepository.save(WalletBalance.builder()
                    .wallet(wallet)
                    .currencyCode(CurrencyType.KRW)
                    .balance(BigDecimal.ZERO)
                    .build());
            return wallet;
        } catch (DataIntegrityViolationException race) {
            // findByUserPublicId(상단)와 saveAndFlush 사이에 다른 트랜잭션이 먼저 생성한 경우.
            // user_public_id UNIQUE에 걸리며, 이때는 기존 지갑을 재조회해 반환(멱등 흡수).
            return walletRepository.findByUserPublicId(userPublicId)
                    .orElseThrow(() -> race); // 진짜로 없으면 원 예외를 다시 던진다(예외적 케이스).
        }
    }

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

    @Override
    public ExchangeRateWidgetResponse getExchangeRates(String currencyCodes) {
        List<CurrencyType> targets = resolveTargetCurrencies(currencyCodes);

        // 모든 항목이 같은 "조회 기준 시각"을 갖도록 한 번만 캡처해 전달한다.
        Instant asOf = Instant.now();

        List<ExchangeRateWidgetResponse.RateItem> items = new ArrayList<>();
        for (CurrencyType currency : targets) {
            BigDecimal rateToKrw = exchangeRateClient.getRateToKrw(currency);
            if (rateToKrw == null) {
                // 지원 통화(CurrencyType)에는 있으나 환율 소스에 값이 없는 경우(cron 미실행/TTL 만료) 포함.
                // /wallets/me 와 동일 정책으로 TRANSFER4002 처리(미지원 통화 = 환율 미존재).
                throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY);
            }
            // 직전 환율은 없을 수 있다(첫 실행/TTL 만료) → null 이면 RateItem 에서 등락률 0 처리.
            BigDecimal prevRateToKrw = exchangeRateClient.getPrevRateToKrw(currency);
            items.add(ExchangeRateWidgetResponse.RateItem.of(currency, rateToKrw, prevRateToKrw, asOf));
        }

        return ExchangeRateWidgetResponse.of(items);
    }

    /**
     * {@code currency_codes} 쿼리 파라미터를 대상 통화 목록으로 변환한다.
     *
     * <ul>
     *   <li>null/공백 → 기준 통화(KRW)를 제외한 전체 지원 통화.
     *   <li>콤마 구분 입력 → 정규화(trim/대문자) 후 중복 제거(입력 순서 보존). 미지원 코드가 하나라도
     *       있으면 {@link TransferErrorCode#UNSUPPORTED_CURRENCY}.
     *   <li>콤마/공백만 들어와 유효 통화가 없으면 기본(전체)로 폴백.
     * </ul>
     */
    private List<CurrencyType> resolveTargetCurrencies(String currencyCodes) {
        if (currencyCodes == null || currencyCodes.isBlank()) {
            return supportedExceptKrw();
        }

        List<CurrencyType> result = new ArrayList<>();
        for (String raw : currencyCodes.split(",")) {
            String code = raw.trim().toUpperCase(Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            CurrencyType currency = CurrencyType.fromCode(code)
                    .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));
            if (!result.contains(currency)) {
                result.add(currency);
            }
        }

        return result.isEmpty() ? supportedExceptKrw() : result;
    }

    /** 기준 통화(KRW)를 제외한 전체 지원 통화. 위젯 기본 노출 목록. */
    private List<CurrencyType> supportedExceptKrw() {
        return Arrays.stream(CurrencyType.values())
                .filter(c -> c != CurrencyType.KRW)
                .toList();
    }
}
