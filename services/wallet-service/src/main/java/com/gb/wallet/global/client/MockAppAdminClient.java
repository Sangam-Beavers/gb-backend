package com.gb.wallet.global.client;

import java.math.BigDecimal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발/테스트용 {@link AppAdminClient} 구현체.
 *
 * <p>app-admin-service 없이도 wallet-service가 정상 기동하도록 고정 값을 반환한다.
 * data.sql 초기 시드와 동일한 값(EXCHANGE 1.5%, CASHOUT 2.0%)을 사용한다.
 */
@Component
@Profile({"dev", "test"})
public class MockAppAdminClient implements AppAdminClient {

    private static final FeePolicy EXCHANGE_POLICY = new FeePolicy(
            "PERCENT",
            new BigDecimal("1.5"),
            new BigDecimal("500.0000"),
            new BigDecimal("5000.0000")
    );

    private static final FeePolicy CASHOUT_POLICY = new FeePolicy(
            "PERCENT",
            new BigDecimal("2.0"),
            new BigDecimal("1000.0000"),
            new BigDecimal("10000.0000")
    );

    @Override
    public FeePolicy getExchangeFeePolicy() {
        return EXCHANGE_POLICY;
    }

    @Override
    public FeePolicy getCashoutFeePolicy() {
        return CASHOUT_POLICY;
    }
}
