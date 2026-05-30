package com.gb.wallet.domain.wallet.service.impl;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.service.WalletBalanceWriter;
import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link WalletBalanceWriter} 구현.
 *
 * <p>{@code ensureBalanceRow}는 {@code REQUIRES_NEW}로 호출 측 트랜잭션과 독립 커밋된다. 충전
 * ({@code ChargeServiceImpl})의 메인 트랜잭션이 잔액 행을 비관적 락으로 잠그려면 그 행이 먼저 존재해야
 * 하는데, 존재하지 않는 행은 {@code SELECT … FOR UPDATE}로 잠글 수 없다. 그래서 "행을 만들어 커밋"하는
 * 책임을 별도 트랜잭션으로 분리한다.
 *
 * <p>동시에 같은 {@code (wallet, currency)}를 INSERT하면 한쪽은 {@code uk_wallet_balances_wallet_currency}
 * 위반이 나는데, 행은 이미 존재하므로 무시(흡수)한다. 이렇게 분리하지 않고 메인 트랜잭션에서 직접 INSERT하면
 * 위반이 메인 트랜잭션을 rollback-only로 오염시키고 {@code charge()} 래퍼의
 * {@code DataIntegrityViolationException} catch(멱등성 race 복구 경로)로 잘못 흘러간다.
 */
@Service
@RequiredArgsConstructor
public class WalletBalanceWriterImpl implements WalletBalanceWriter {

    private final WalletBalanceRepository walletBalanceRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureBalanceRow(Wallet wallet, CurrencyType currency) {
        if (walletBalanceRepository.existsByWalletAndCurrencyCode(wallet, currency)) {
            return; // 이미 있으면 no-op
        }
        try {
            walletBalanceRepository.saveAndFlush(WalletBalance.builder()
                    .wallet(wallet)
                    .currencyCode(currency)
                    .balance(BigDecimal.ZERO)
                    .build());
        } catch (DataIntegrityViolationException concurrentCreate) {
            // 다른 트랜잭션이 먼저 같은 (wallet, currency) 행을 만들었다 → 행은 존재하므로 무시한다.
            // 이어지는 메인 트랜잭션의 FOR UPDATE가 그 행을 잠그고 직렬화한다.
        }
    }
}
