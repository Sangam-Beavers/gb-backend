package com.gb.wallet.domain.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository.FeeByTypeCurrencyProjection;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link TransactionRepository#sumFeeByTypeAndCurrency} 통합 검증(H2 MySQL 모드).
 * 수익(매출) 집계가 ① COMPLETED 만 ② EXCHANGE/REMITTANCE 만 ③ 유형·통화별로 fee 를 합산하는지 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TransactionRepositoryFeeRevenueTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private WalletRepository walletRepository;

    private Wallet wallet;

    private Transaction tx(TransactionType type, CurrencyType currency,
                           TransactionStatus status, String fee) {
        return Transaction.builder()
                .publicId(UUID.randomUUID().toString())
                .wallet(wallet)
                .type(type)
                .amount(new BigDecimal("100000.0000"))
                .currencyCode(currency)
                .fee(new BigDecimal(fee))
                .status(status)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
    }

    @Test
    @DisplayName("sumFeeByTypeAndCurrency — COMPLETED·EXCHANGE/REMITTANCE 만, 유형·통화별 fee 합산")
    void sumFeeByTypeAndCurrency() {
        wallet = walletRepository.save(Wallet.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(UUID.randomUUID().toString())
                .status(WalletStatus.ACTIVE)
                .build());

        // 집계 대상
        transactionRepository.save(tx(TransactionType.EXCHANGE, CurrencyType.KRW, TransactionStatus.COMPLETED, "1000.0000"));
        transactionRepository.save(tx(TransactionType.EXCHANGE, CurrencyType.KRW, TransactionStatus.COMPLETED, "2000.0000"));
        transactionRepository.save(tx(TransactionType.EXCHANGE, CurrencyType.USD, TransactionStatus.COMPLETED, "500.0000"));
        transactionRepository.save(tx(TransactionType.REMITTANCE, CurrencyType.KRW, TransactionStatus.COMPLETED, "800.0000"));
        // 제외 대상: 미완료 / 충전·내부송금
        transactionRepository.save(tx(TransactionType.EXCHANGE, CurrencyType.KRW, TransactionStatus.PENDING, "9999.0000"));
        transactionRepository.save(tx(TransactionType.CHARGE, CurrencyType.KRW, TransactionStatus.COMPLETED, "7777.0000"));
        transactionRepository.save(tx(TransactionType.INTERNAL_TRANSFER, CurrencyType.KRW, TransactionStatus.COMPLETED, "6666.0000"));

        List<FeeByTypeCurrencyProjection> rows = transactionRepository.sumFeeByTypeAndCurrency(null, null);

        // EXCHANGE/KRW=3000, EXCHANGE/USD=500, REMITTANCE/KRW=800 — 총 3행, 제외 대상은 안 잡힌다.
        assertThat(rows).hasSize(3);
        BigDecimal exchangeKrw = feeOf(rows, TransactionType.EXCHANGE, CurrencyType.KRW);
        BigDecimal exchangeUsd = feeOf(rows, TransactionType.EXCHANGE, CurrencyType.USD);
        BigDecimal remittanceKrw = feeOf(rows, TransactionType.REMITTANCE, CurrencyType.KRW);

        assertThat(exchangeKrw).isEqualByComparingTo("3000.0000");
        assertThat(exchangeUsd).isEqualByComparingTo("500.0000");
        assertThat(remittanceKrw).isEqualByComparingTo("800.0000");
    }

    private static BigDecimal feeOf(List<FeeByTypeCurrencyProjection> rows,
                                    TransactionType type, CurrencyType currency) {
        return rows.stream()
                .filter(r -> r.getType() == type && r.getCurrencyCode() == currency)
                .map(FeeByTypeCurrencyProjection::getTotalFee)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }
}
