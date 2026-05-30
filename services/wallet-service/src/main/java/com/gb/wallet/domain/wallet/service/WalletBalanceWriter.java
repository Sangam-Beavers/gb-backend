package com.gb.wallet.domain.wallet.service;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.global.common.enums.CurrencyType;

/**
 * 지갑 잔액 행(wallet_balances) 쓰기 헬퍼.
 *
 * <p>충전 등 잔액 변경 흐름은 같은 (wallet, currency) 행을 비관적 락(SELECT … FOR UPDATE)으로 잠가
 * 직렬화하는데, <b>존재하지 않는 행은 잠글 수 없다</b>. 그래서 "행을 먼저 보장(get-or-create)"하는 책임을
 * 별도 트랜잭션 경계로 노출한다. 조회 전용 {@code WalletService}와 분리해 쓰기 책임만 담는다.
 */
public interface WalletBalanceWriter {

    /**
     * {@code (wallet, currency)} 0원 잔액 행을 멱등하게 보장한다. 별도 트랜잭션({@code REQUIRES_NEW})으로
     * 독립 커밋되며, 동시 생성으로 {@code uk_wallet_balances_wallet_currency} 위반이 나도 흡수한다 —
     * 호출 직후 호출 측은 해당 행을 {@code FOR UPDATE}로 잠글 수 있다.
     */
    void ensureBalanceRow(Wallet wallet, CurrencyType currency);
}