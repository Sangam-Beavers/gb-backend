package com.gb.wallet.domain.exchange.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.exchange.dto.QuoteData;
import com.gb.wallet.domain.exchange.dto.request.ExchangeExecuteRequest;
import com.gb.wallet.domain.exchange.dto.request.QuoteRequest;
import com.gb.wallet.domain.exchange.dto.response.ExchangeResponse;
import com.gb.wallet.domain.exchange.dto.response.QuoteResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse.CurrencyInfo;
import com.gb.wallet.domain.exchange.repository.QuoteRedisRepository;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.domain.wallet.service.WalletBalanceWriter;
import com.gb.wallet.global.client.ExchangeRateClient;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.ExchangeType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.ExchangeErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 환전 Service 단위 테스트(지원통화/견적/실행/내역).
 *
 * <p>DB·Redis·외부 환율은 mock으로 대체한다. 환율 계산, 견적 만료, 잔액 부족, 본인 아님,
 * 멱등 재반환 등 핵심 분기를 검증한다. (실제 잔액 변경·동시성은 통합테스트 영역)
 */
@ExtendWith(MockitoExtension.class)
class ExchangeServiceImplTest {

    @Mock private WalletRepository walletRepository;
    @Mock private WalletBalanceRepository walletBalanceRepository;
    @Mock private WalletBalanceWriter walletBalanceWriter;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionAuditLogRepository auditLogRepository;
    @Mock private QuoteRedisRepository quoteRedisRepository;
    @Mock private ExchangeRateClient exchangeRateClient;

    @InjectMocks private ExchangeServiceImpl exchangeService;

    private static final String USER = "user-uuid-1";

    // ───────────────────── 지원 통화 목록 ─────────────────────

    @Test
    @DisplayName("지원 통화 목록은 CurrencyType 전체를 코드·이름·기호와 함께 반환한다")
    void getSupportedCurrencies_전체통화_반환() {
        SupportedCurrenciesResponse response = exchangeService.getSupportedCurrencies();

        List<CurrencyInfo> currencies = response.getCurrencies();
        assertThat(currencies).hasSize(CurrencyType.values().length);
        assertThat(currencies).extracting(CurrencyInfo::getCurrencyCode)
                .containsExactlyInAnyOrder("KRW", "USD", "PHP", "VND");
    }

    // ───────────────────── 견적 ─────────────────────

    @Test
    @DisplayName("견적: 원화 10만원→USD, 환율 1380·수수료 0.5%로 수령액을 계산해 Redis에 저장한다")
    void createQuote_계산_저장() {
        // given — KRW→USD, 100,000원
        QuoteRequest request = new QuoteRequest();
        ReflectionTestUtils.setField(request, "exchangeType", "EXCHANGE");
        ReflectionTestUtils.setField(request, "fromCurrencyCode", "KRW");
        ReflectionTestUtils.setField(request, "toCurrencyCode", "USD");
        ReflectionTestUtils.setField(request, "amount", "100000.0000");
        when(exchangeRateClient.getRateToKrw(CurrencyType.KRW)).thenReturn(new BigDecimal("1"));
        when(exchangeRateClient.getRateToKrw(CurrencyType.USD)).thenReturn(new BigDecimal("1380"));

        // when
        QuoteResponse response = exchangeService.createQuote(USER, request);

        // then — 수수료 = 100000 * 0.5% = 500, 수령액 = (100000-500)/1380 ≈ 72.1014
        assertThat(response.getFee()).isEqualTo("500.0000");
        assertThat(response.getReceiveCurrencyCode()).isEqualTo("USD");
        assertThat(new BigDecimal(response.getReceiveAmount()))
                .isEqualByComparingTo(new BigDecimal("72.1014"));
        // 견적이 Redis에 저장돼야 한다.
        verify(quoteRedisRepository).save(any(QuoteData.class));
    }

    @Test
    @DisplayName("견적: 지원하지 않는 통화면 TRANSFER4002")
    void createQuote_미지원통화_거절() {
        QuoteRequest request = new QuoteRequest();
        ReflectionTestUtils.setField(request, "exchangeType", "EXCHANGE");
        ReflectionTestUtils.setField(request, "fromCurrencyCode", "KRW");
        ReflectionTestUtils.setField(request, "toCurrencyCode", "JPY"); // 미지원
        ReflectionTestUtils.setField(request, "amount", "100000.0000");

        assertThatThrownBy(() -> exchangeService.createQuote(USER, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_CURRENCY);

        verify(quoteRedisRepository, never()).save(any());
    }

    // ───────────────────── 실행 ─────────────────────

    @Test
    @DisplayName("실행: 견적이 만료(Redis에 없음)면 EXCHANGE4002")
    void execute_견적만료() {
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(quoteRedisRepository.find("quote-1")).thenReturn(Optional.empty()); // 만료

        assertThatThrownBy(() -> exchangeService.execute(USER, "idem-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ExchangeErrorCode.QUOTE_EXPIRED);
    }

    @Test
    @DisplayName("실행: 견적 발급자가 아니면 COMMON4031")
    void execute_타인견적_거절() {
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        // 견적 주인은 다른 사용자
        QuoteData othersQuote = new QuoteData("quote-1", "other-user", ExchangeType.EXCHANGE,
                CurrencyType.KRW, CurrencyType.USD, new BigDecimal("100000"),
                new BigDecimal("1380"), new BigDecimal("500"), CurrencyType.KRW,
                new BigDecimal("72.1014"), CurrencyType.USD);
        when(quoteRedisRepository.find("quote-1")).thenReturn(Optional.of(othersQuote));

        assertThatThrownBy(() -> exchangeService.execute(USER, "idem-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .extracting(Object::toString)
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("실행: 잔액 부족이면 WALLET4002 (executeInTransaction)")
    void executeInTransaction_잔액부족() {
        QuoteData quote = new QuoteData("quote-1", USER, ExchangeType.EXCHANGE,
                CurrencyType.KRW, CurrencyType.USD, new BigDecimal("100000"),
                new BigDecimal("1380"), new BigDecimal("500"), CurrencyType.KRW,
                new BigDecimal("72.1014"), CurrencyType.USD);

        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(wallet));
        // from(KRW) 잔액 행: 잔액 1만원만 있어 10만원 환전 불가
        WalletBalance fromBalance = WalletBalance.builder()
                .wallet(wallet).currencyCode(CurrencyType.KRW).balance(new BigDecimal("10000")).build();
        WalletBalance toBalance = WalletBalance.builder()
                .wallet(wallet).currencyCode(CurrencyType.USD).balance(BigDecimal.ZERO).build();
        when(walletBalanceRepository.findForUpdateByWalletAndCurrency(eq(wallet), any(CurrencyType.class)))
                .thenAnswer(inv -> {
                    CurrencyType c = inv.getArgument(1);
                    return Optional.of(c == CurrencyType.KRW ? fromBalance : toBalance);
                });

        assertThatThrownBy(() -> exchangeService.executeInTransaction(USER, "idem-1", quote))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(WalletErrorCode.INSUFFICIENT_BALANCE);

        // 잔액 부족이면 거래를 저장하지 않는다.
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("실행: 정상 환전이면 from 차감·to 증가 후 거래를 저장한다")
    void executeInTransaction_성공() {
        QuoteData quote = new QuoteData("quote-1", USER, ExchangeType.EXCHANGE,
                CurrencyType.KRW, CurrencyType.USD, new BigDecimal("100000"),
                new BigDecimal("1380"), new BigDecimal("500"), CurrencyType.KRW,
                new BigDecimal("72.1014"), CurrencyType.USD);

        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(wallet));
        WalletBalance fromBalance = WalletBalance.builder()
                .wallet(wallet).currencyCode(CurrencyType.KRW).balance(new BigDecimal("500000")).build();
        WalletBalance toBalance = WalletBalance.builder()
                .wallet(wallet).currencyCode(CurrencyType.USD).balance(BigDecimal.ZERO).build();
        when(walletBalanceRepository.findForUpdateByWalletAndCurrency(eq(wallet), any(CurrencyType.class)))
                .thenAnswer(inv -> {
                    CurrencyType c = inv.getArgument(1);
                    return Optional.of(c == CurrencyType.KRW ? fromBalance : toBalance);
                });
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        ExchangeResponse response = exchangeService.executeInTransaction(USER, "idem-1", quote);

        // then — 잔액 변경 확인
        assertThat(fromBalance.getBalance()).isEqualByComparingTo(new BigDecimal("400000")); // 500000-100000
        assertThat(toBalance.getBalance()).isEqualByComparingTo(new BigDecimal("72.1014"));   // 0+72.1014
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        verify(transactionRepository).save(any(Transaction.class));
    }
}
