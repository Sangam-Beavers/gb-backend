package com.gb.wallet.domain.transaction.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gb.wallet.domain.transaction.dto.response.TransactionHistoryItemResponse;
import com.gb.wallet.domain.transaction.dto.response.TransactionListResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    @Mock private MemberClient memberClient;
    @InjectMocks private TransactionServiceImpl service;

    private static final String USER = "user-uuid";

    @Test
    @DisplayName("getMyTransactions: page/size를 최근순(created_at DESC) PageRequest로 전달한다")
    void getMyTransactions_PageRequest_정렬() {
        when(transactionRepository.findByMineSendingOrReceiving(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 15), 0));

        service.getMyTransactions(USER, 2, 15);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByMineSendingOrReceiving(eq(USER), captor.capture());
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
        Transaction charge = tx("c-1", wallet, null, TransactionType.CHARGE, new BigDecimal("500000"),
                CurrencyType.KRW, BigDecimal.ZERO, null, null, null);
        Transaction remittance = tx("r-1", wallet, null, TransactionType.REMITTANCE, new BigDecimal("100000"),
                CurrencyType.KRW, new BigDecimal("3000"), "홍길동", null, null);
        Transaction exchange = tx("e-1", wallet, null, TransactionType.EXCHANGE, new BigDecimal("100000"),
                CurrencyType.KRW, new BigDecimal("500"), null, new BigDecimal("72.1014"), CurrencyType.USD);
        when(transactionRepository.findByMineSendingOrReceiving(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(charge, remittance, exchange), PageRequest.of(0, 20), 3));

        TransactionListResponse response = service.getMyTransactions(USER, 0, 20);

        assertThat(response.getTransactions()).hasSize(3);
        assertThat(response.getTransactions()).extracting(TransactionHistoryItemResponse::getType)
                .containsExactly("CHARGE", "REMITTANCE", "EXCHANGE");
        // 모두 본인이 송신자(wallet=USER)라 direction=OUT.
        assertThat(response.getTransactions()).extracting(TransactionHistoryItemResponse::getDirection)
                .containsExactly("OUT", "OUT", "OUT");

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
    @DisplayName("getMyTransactions: INTERNAL_TRANSFER 송신자=OUT, 수신자=IN으로 direction을 매핑한다")
    void getMyTransactions_internalTransfer_OUT_IN_매핑() {
        Wallet me = Wallet.builder().publicId("w-me").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Wallet other = Wallet.builder().publicId("w-other").userPublicId("other-uuid")
                .status(WalletStatus.ACTIVE).build();

        // ① 내가 송신자(wallet=me), 상대(other)에게 보낸 거래 → 본인 기준 OUT
        Transaction sent = tx("it-out", me, other, TransactionType.INTERNAL_TRANSFER,
                new BigDecimal("10000"), CurrencyType.KRW, BigDecimal.ZERO, null, null, null);
        // ② 내가 수신자(receiverWallet=me), 상대(other)가 보낸 거래 → 본인 기준 IN
        Transaction received = tx("it-in", other, me, TransactionType.INTERNAL_TRANSFER,
                new BigDecimal("20000"), CurrencyType.KRW, BigDecimal.ZERO, null, null, null);
        when(transactionRepository.findByMineSendingOrReceiving(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sent, received), PageRequest.of(0, 20), 2));

        TransactionListResponse response = service.getMyTransactions(USER, 0, 20);

        assertThat(response.getTransactions()).extracting(TransactionHistoryItemResponse::getPublicId)
                .containsExactly("it-out", "it-in");
        assertThat(response.getTransactions()).extracting(TransactionHistoryItemResponse::getDirection)
                .containsExactly("OUT", "IN");
    }

    @Test
    @DisplayName("getMyTransactions: INTERNAL_TRANSFER 거래 상대 닉네임을 배치 1회 조회로 채운다(OUT=받는 사람, IN=보낸 사람)")
    void getMyTransactions_거래상대_닉네임_매핑() {
        Wallet me = Wallet.builder().publicId("w-me").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Wallet other = Wallet.builder().publicId("w-other").userPublicId("other-uuid")
                .status(WalletStatus.ACTIVE).build();
        // 내가 보낸 거래(OUT, 상대=other) + 내가 받은 거래(IN, 상대=other). 양쪽 모두 상대는 other-uuid 1명.
        Transaction sent = tx("it-out", me, other, TransactionType.INTERNAL_TRANSFER,
                new BigDecimal("10000"), CurrencyType.KRW, BigDecimal.ZERO, null, null, null);
        Transaction received = tx("it-in", other, me, TransactionType.INTERNAL_TRANSFER,
                new BigDecimal("20000"), CurrencyType.KRW, BigDecimal.ZERO, null, null, null);
        when(transactionRepository.findByMineSendingOrReceiving(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sent, received), PageRequest.of(0, 20), 2));
        when(memberClient.getMembers(any())).thenReturn(Map.of(
                "other-uuid", new MemberInfo("other-uuid", null, "Nguyen", "하노이댁", "VN", false)));

        TransactionListResponse response = service.getMyTransactions(USER, 0, 20);

        // OUT=받는 사람(other), IN=보낸 사람(other) — 둘 다 상대 닉네임이 채워진다.
        assertThat(response.getTransactions()).extracting(TransactionHistoryItemResponse::getCounterpartyNickname)
                .containsExactly("하노이댁", "하노이댁");
        // 건별 HTTP가 아니라 배치 1회만 호출(중복 상대 id는 distinct로 1건).
        verify(memberClient, times(1)).getMembers(any());
    }

    @Test
    @DisplayName("getMyTransactions: 송금 외 유형(CHARGE/REMITTANCE/EXCHANGE)은 상대 닉네임 null + MemberClient 미호출")
    void getMyTransactions_비송금_상대없음() {
        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Transaction charge = tx("c-1", wallet, null, TransactionType.CHARGE, new BigDecimal("500000"),
                CurrencyType.KRW, BigDecimal.ZERO, null, null, null);
        Transaction remittance = tx("r-1", wallet, null, TransactionType.REMITTANCE, new BigDecimal("100000"),
                CurrencyType.KRW, new BigDecimal("3000"), "홍길동", null, null);
        when(transactionRepository.findByMineSendingOrReceiving(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(charge, remittance), PageRequest.of(0, 20), 2));

        TransactionListResponse response = service.getMyTransactions(USER, 0, 20);

        // 앱 사용자 상대가 없는 유형은 counterparty_nickname=null (REMITTANCE는 receiver_name로 별도 표기).
        assertThat(response.getTransactions()).extracting(TransactionHistoryItemResponse::getCounterpartyNickname)
                .containsExactly(null, null);
        assertThat(response.getTransactions().get(1).getReceiverName()).isEqualTo("홍길동");
        // 조회할 상대가 없으면 member-service를 아예 호출하지 않는다(불필요 HTTP 차단).
        verify(memberClient, never()).getMembers(any());
    }

    @Test
    @DisplayName("getMyTransactions: 거래가 없으면 WALLET4001을 던지지 않고 빈 배열 + 메타를 반환한다")
    void getMyTransactions_빈_결과() {
        when(transactionRepository.findByMineSendingOrReceiving(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        TransactionListResponse response = service.getMyTransactions(USER, 0, 20);

        assertThat(response.getTransactions()).isEmpty();
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(20);
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("getMyTransactions: 요청자 본인의 user_public_id로만 조회한다(OR 조회 호출)")
    void getMyTransactions_본인만_조회() {
        when(transactionRepository.findByMineSendingOrReceiving(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getMyTransactions(USER, 0, 20);

        verify(transactionRepository).findByMineSendingOrReceiving(eq(USER), any(Pageable.class));
    }

    /**
     * 헬퍼. {@code receiverWallet} 파라미터 추가 — INTERNAL_TRANSFER의 수신자 시점(direction=IN) 검증 위해.
     */
    private Transaction tx(String publicId, Wallet wallet, Wallet receiverWallet, TransactionType type,
                           BigDecimal amount, CurrencyType currency, BigDecimal fee, String receiverName,
                           BigDecimal receiveAmount, CurrencyType receiveCurrency) {
        Transaction t = Transaction.builder()
                .publicId(publicId)
                .wallet(wallet)
                .receiverWallet(receiverWallet)
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
