package com.gb.wallet.domain.transaction.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gb.wallet.domain.transaction.dto.response.TransactionHistoryItemResponse;
import com.gb.wallet.domain.transaction.dto.response.TransactionListResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link TransactionServiceImpl}(전자지갑 거래내역 조회) 단위 테스트. DB 없이 Mockito로만.
 * 페이지 요청 구성(정렬), 전 유형 매핑, 빈 결과, 본인 필터링을 검증한다(getExchanges 패턴 미러).
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock private TransactionRepository transactionRepository;
    @InjectMocks private TransactionServiceImpl service;

    private static final String USER = "user-uuid";

    @Test
    @DisplayName("getMyTransactions: page/size를 최근순(created_at DESC) PageRequest로 전달한다")
    void getMyTransactions_PageRequest_정렬() {
        when(transactionRepository.findByWallet_UserPublicId(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 15), 0));

        service.getMyTransactions(USER, 2, 15);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByWallet_UserPublicId(eq(USER), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(15);
        Sort.Order order = pageable.getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("getMyTransactions: 전 유형 거래를 항목으로 매핑하고 페이지 메타를 채운다")
    void getMyTransactions_전유형_매핑() {
        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Transaction charge = tx("c-1", wallet, TransactionType.CHARGE, new BigDecimal("500000"),
                CurrencyType.KRW, BigDecimal.ZERO, null, null, null);
        Transaction remittance = tx("r-1", wallet, TransactionType.REMITTANCE, new BigDecimal("100000"),
                CurrencyType.KRW, new BigDecimal("3000"), "홍길동", null, null);
        Transaction exchange = tx("e-1", wallet, TransactionType.EXCHANGE, new BigDecimal("100000"),
                CurrencyType.KRW, new BigDecimal("500"), null, new BigDecimal("72.1014"), CurrencyType.USD);
        when(transactionRepository.findByWallet_UserPublicId(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(charge, remittance, exchange), PageRequest.of(0, 20), 3));

        TransactionListResponse response = service.getMyTransactions(USER, 0, 20);

        assertThat(response.getTransactions()).hasSize(3);
        assertThat(response.getTransactions()).extracting(TransactionHistoryItemResponse::getType)
                .containsExactly("CHARGE", "REMITTANCE", "EXCHANGE");

        TransactionHistoryItemResponse chargeItem = response.getTransactions().get(0);
        assertThat(chargeItem.getPublicId()).isEqualTo("c-1");
        assertThat(chargeItem.getAmount()).isEqualTo("500000.0000");   // string, 소수 4자리
        assertThat(chargeItem.getFee()).isEqualTo("0.0000");
        assertThat(chargeItem.getReceiveAmount()).isNull();             // CHARGE는 수령 정보 없음
        assertThat(chargeItem.getReceiveCurrencyCode()).isNull();
        assertThat(chargeItem.getReceiverName()).isNull();
        assertThat(chargeItem.getCreatedAt()).isEqualTo("2026-05-26T04:15:30Z");

        assertThat(response.getTransactions().get(1).getReceiverName()).isEqualTo("홍길동");

        TransactionHistoryItemResponse exchangeItem = response.getTransactions().get(2);
        assertThat(exchangeItem.getReceiveAmount()).isEqualTo("72.1014");
        assertThat(exchangeItem.getReceiveCurrencyCode()).isEqualTo("USD");

        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(20);
        assertThat(response.getTotalElements()).isEqualTo(3);
        assertThat(response.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("getMyTransactions: 거래가 없으면 WALLET4001을 던지지 않고 빈 배열 + 메타를 반환한다")
    void getMyTransactions_빈_결과() {
        when(transactionRepository.findByWallet_UserPublicId(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        TransactionListResponse response = service.getMyTransactions(USER, 0, 20);

        assertThat(response.getTransactions()).isEmpty();
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(20);
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("getMyTransactions: 요청자 본인의 user_public_id로만 조회한다")
    void getMyTransactions_본인만_조회() {
        when(transactionRepository.findByWallet_UserPublicId(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getMyTransactions(USER, 0, 20);

        verify(transactionRepository).findByWallet_UserPublicId(eq(USER), any(Pageable.class));
    }

    private Transaction tx(String publicId, Wallet wallet, TransactionType type, BigDecimal amount,
                           CurrencyType currency, BigDecimal fee, String receiverName,
                           BigDecimal receiveAmount, CurrencyType receiveCurrency) {
        Transaction t = Transaction.builder()
                .publicId(publicId)
                .wallet(wallet)
                .type(type)
                .amount(amount)
                .currencyCode(currency)
                .fee(fee)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(publicId + "-key")
                .receiverName(receiverName)
                .receiveAmount(receiveAmount)
                .receiveCurrencyCode(receiveCurrency)
                .build();
        // created_at은 @CreatedDate 대상이라 빌더에 없다 — 매핑(toUtcZ) 검증을 위해 reflection으로 고정.
        ReflectionTestUtils.setField(t, "createdAt", LocalDateTime.of(2026, 5, 26, 4, 15, 30));
        return t;
    }
}
