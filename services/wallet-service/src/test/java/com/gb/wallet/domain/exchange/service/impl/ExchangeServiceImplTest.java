package com.gb.wallet.domain.exchange.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.exchange.dto.QuoteData;
import com.gb.wallet.domain.exchange.dto.request.ExchangeExecuteRequest;
import com.gb.wallet.domain.exchange.dto.request.QuoteRequest;
import com.gb.wallet.domain.exchange.dto.response.ExchangeListResponse;
import com.gb.wallet.domain.exchange.dto.response.ExchangeResponse;
import com.gb.wallet.domain.exchange.dto.response.QuoteResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse.CurrencyInfo;
import com.gb.wallet.domain.exchange.repository.QuoteRedisRepository;
import com.gb.wallet.domain.exchange.service.ExchangeService;
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
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.ExchangeErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.IdempotencyCacheHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    @Mock private IdempotencyCacheHelper idempotencyCacheHelper;
    @Mock private ObjectMapper objectMapper;
    @Mock private ExchangeService self;

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

    @Test
    @DisplayName("견적: exchange_type과 from/to 방향이 모순이면 COMMON4001 (저장 안 함)")
    void createQuote_방향불일치_COMMON4001() {
        // EXCHANGE(원화→외화)인데 from=USD, to=KRW(외화→원화) — 라벨↔방향 모순. 환율 조회 전에 차단된다.
        QuoteRequest request = new QuoteRequest();
        ReflectionTestUtils.setField(request, "exchangeType", "EXCHANGE");
        ReflectionTestUtils.setField(request, "fromCurrencyCode", "USD");
        ReflectionTestUtils.setField(request, "toCurrencyCode", "KRW");
        ReflectionTestUtils.setField(request, "amount", "100.0000");

        assertThatThrownBy(() -> exchangeService.createQuote(USER, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verify(quoteRedisRepository, never()).save(any());
        verify(exchangeRateClient, never()).getRateToKrw(any()); // 방향 검증 실패는 외부 환율 조회 전에 차단된다
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
                .isEqualTo(CommonErrorCode.FORBIDDEN);
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

    // ───────────────────── 실행 — 멱등성 Layer 1(Redis 캐시) ─────────────────────

    @Test
    @DisplayName("실행 Layer 1: 캐시 hit이면 DB·견적 조회 없이 캐시 응답을 그대로 반환")
    void execute_캐시_hit() throws Exception {
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        ExchangeResponse cached = ExchangeResponse.builder()
                .publicId("ex-1").exchangeType("EXCHANGE").status("COMPLETED").build();
        String cacheKey = "exchange:idem-1:" + USER; // (key, user)로 스코프
        when(idempotencyCacheHelper.get(cacheKey)).thenReturn(Optional.of("{\"cached\":\"json\"}"));
        when(objectMapper.readValue("{\"cached\":\"json\"}", ExchangeResponse.class)).thenReturn(cached);

        ExchangeResponse result = exchangeService.execute(USER, "idem-1", request);

        assertThat(result).isSameAs(cached);
        verify(transactionRepository, never()).findByIdempotencyKey(any());
        verify(quoteRedisRepository, never()).find(any());
        verify(idempotencyCacheHelper, never()).set(any(), any());
    }

    @Test
    @DisplayName("실행 Layer 1: 캐시 miss → 정상 실행 후 결과를 스코프 키로 캐시에 채운다(set 호출)")
    void execute_캐시_miss_후_채움() throws Exception {
        ReflectionTestUtils.setField(exchangeService, "self", self); // self-proxy 위임 검증용
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        String cacheKey = "exchange:idem-1:" + USER;
        QuoteData quote = new QuoteData("quote-1", USER, ExchangeType.EXCHANGE,
                CurrencyType.KRW, CurrencyType.USD, new BigDecimal("100000"),
                new BigDecimal("1380"), new BigDecimal("500"), CurrencyType.KRW,
                new BigDecimal("72.1014"), CurrencyType.USD);
        ExchangeResponse response = ExchangeResponse.builder().publicId("ex-1").status("COMPLETED").build();
        when(idempotencyCacheHelper.get(cacheKey)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(quoteRedisRepository.find("quote-1")).thenReturn(Optional.of(quote));            // 소유자 확인(비파괴)
        when(quoteRedisRepository.getAndDelete("quote-1")).thenReturn(Optional.of(quote));    // 원자 소비
        when(self.executeInTransaction(USER, "idem-1", quote)).thenReturn(response);
        when(objectMapper.writeValueAsString(response)).thenReturn("{\"json\":\"ok\"}");

        ExchangeResponse result = exchangeService.execute(USER, "idem-1", request);

        assertThat(result).isSameAs(response);
        verify(idempotencyCacheHelper).set(cacheKey, "{\"json\":\"ok\"}");
        verify(quoteRedisRepository).getAndDelete("quote-1"); // 실행 전 원자 소비(이중 환전 차단, WEXB-01)
        verify(quoteRedisRepository, never()).delete(any());  // best-effort delete 경로 폐기
    }

    // ───────────────────── 내역 목록 ─────────────────────

    @Test
    @DisplayName("내역 목록: 페이지 조회 결과를 exchanges 배열 + 페이지 메타로 매핑한다")
    void getExchanges_매핑() {
        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Transaction tx = Transaction.builder()
                .publicId("ex-1").wallet(wallet).type(TransactionType.EXCHANGE)
                .amount(new BigDecimal("100000")).currencyCode(CurrencyType.KRW)
                .fee(new BigDecimal("500")).status(TransactionStatus.COMPLETED)
                .idempotencyKey("k").receiveAmount(new BigDecimal("72.1014"))
                .receiveCurrencyCode(CurrencyType.USD).exchangeRate(new BigDecimal("1380"))
                .toAmount(new BigDecimal("72.1014")).build();
        ReflectionTestUtils.setField(tx, "createdAt", LocalDateTime.of(2026, 5, 26, 5, 30, 0));
        when(transactionRepository.findByWallet_UserPublicIdAndType(
                eq(USER), eq(TransactionType.EXCHANGE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx), PageRequest.of(0, 20), 1));

        ExchangeListResponse response = exchangeService.getExchanges(USER, 0, 20);

        assertThat(response.getExchanges()).hasSize(1);
        assertThat(response.getExchanges().get(0).getPublicId()).isEqualTo("ex-1");
        // 수령 통화가 KRW가 아니면(USD) EXCHANGE로 역산된다.
        assertThat(response.getExchanges().get(0).getExchangeType()).isEqualTo("EXCHANGE");
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(20);
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getTotalPages()).isEqualTo(1);
    }

    // ───────────────────── EX-T1: RE_EXCHANGE(외화→원화) 견적·실행·역산 ─────────────────────

    @Test
    @DisplayName("견적 RE_EXCHANGE: USD 100→원화, 역방향 환율·수수료·수령액을 계산한다(노출 환율=from 외화)")
    void createQuote_재환전_계산() {
        // given — RE_EXCHANGE, USD 100 → KRW
        QuoteRequest request = new QuoteRequest();
        ReflectionTestUtils.setField(request, "exchangeType", "RE_EXCHANGE");
        ReflectionTestUtils.setField(request, "fromCurrencyCode", "USD");
        ReflectionTestUtils.setField(request, "toCurrencyCode", "KRW");
        ReflectionTestUtils.setField(request, "amount", "100.0000");
        when(exchangeRateClient.getRateToKrw(CurrencyType.USD)).thenReturn(new BigDecimal("1380"));
        when(exchangeRateClient.getRateToKrw(CurrencyType.KRW)).thenReturn(new BigDecimal("1"));

        // when
        QuoteResponse response = exchangeService.createQuote(USER, request);

        // then — amountInKrw = 100*1380 = 138000, 수수료 = 138000*0.5% = 690, 수령 = (138000-690)/1 = 137310
        assertThat(response.getFee()).isEqualTo("690.0000");
        assertThat(response.getFeeCurrencyCode()).isEqualTo("KRW");
        assertThat(response.getReceiveCurrencyCode()).isEqualTo("KRW");
        assertThat(new BigDecimal(response.getReceiveAmount()))
                .isEqualByComparingTo(new BigDecimal("137310.0000"));
        // RE_EXCHANGE는 from(외화=USD) 환율을 노출한다("1 외화→KRW" 표기).
        assertThat(new BigDecimal(response.getExchangeRate()))
                .isEqualByComparingTo(new BigDecimal("1380"));
        verify(quoteRedisRepository).save(any(QuoteData.class));
    }

    @Test
    @DisplayName("실행 RE_EXCHANGE: 외화(USD) 차감·원화(KRW) 증가 후 거래를 저장하고 RE_EXCHANGE로 응답한다")
    void executeInTransaction_재환전_성공() {
        QuoteData quote = new QuoteData("quote-re", USER, ExchangeType.RE_EXCHANGE,
                CurrencyType.USD, CurrencyType.KRW, new BigDecimal("100"),
                new BigDecimal("1380"), new BigDecimal("690"), CurrencyType.KRW,
                new BigDecimal("137310.0000"), CurrencyType.KRW);

        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(wallet));
        WalletBalance fromBalance = WalletBalance.builder()
                .wallet(wallet).currencyCode(CurrencyType.USD).balance(new BigDecimal("500")).build();
        WalletBalance toBalance = WalletBalance.builder()
                .wallet(wallet).currencyCode(CurrencyType.KRW).balance(BigDecimal.ZERO).build();
        when(walletBalanceRepository.findForUpdateByWalletAndCurrency(eq(wallet), any(CurrencyType.class)))
                .thenAnswer(inv -> {
                    CurrencyType c = inv.getArgument(1);
                    return Optional.of(c == CurrencyType.USD ? fromBalance : toBalance);
                });
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        ExchangeResponse response = exchangeService.executeInTransaction(USER, "idem-re", quote);

        // then — from(USD) 차감, to(KRW) 증가
        assertThat(fromBalance.getBalance()).isEqualByComparingTo(new BigDecimal("400"));      // 500-100
        assertThat(toBalance.getBalance()).isEqualByComparingTo(new BigDecimal("137310.0000")); // 0+137310
        assertThat(response.getExchangeType()).isEqualTo("RE_EXCHANGE");
        assertThat(response.getFromCurrencyCode()).isEqualTo("USD");
        assertThat(response.getToCurrencyCode()).isEqualTo("KRW");
        assertThat(response.getReceiveCurrencyCode()).isEqualTo("KRW");
        verify(transactionRepository).save(any(Transaction.class));
    }

    // ───────────────────── EX-T2: 멱등성 Layer 2(DB)·Layer 3(race) ─────────────────────

    @Test
    @DisplayName("실행 Layer 2: 캐시 miss + DB에 동일 키 거래 존재 → 견적 조회·재실행 없이 첫 거래를 재반환")
    void execute_Layer2_prior_재반환() {
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        String cacheKey = "exchange:idem-1:" + USER;
        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Transaction prior = exchangeTx(wallet, "ex-prior");
        when(idempotencyCacheHelper.get(cacheKey)).thenReturn(Optional.empty()); // 캐시 miss
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(prior));

        ExchangeResponse result = exchangeService.execute(USER, "idem-1", request);

        assertThat(result.getPublicId()).isEqualTo("ex-prior");
        // Layer 2 hit이면 견적 재조회·재실행·잔액 변경이 일어나지 않는다.
        verify(quoteRedisRepository, never()).find(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("실행 Layer 3: 실행 중 UNIQUE race(DataIntegrityViolation) → readPrior로 첫 거래를 재반환(견적 미삭제)")
    void execute_Layer3_race_readPrior() {
        ReflectionTestUtils.setField(exchangeService, "self", self); // self-proxy 위임 검증용
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        String cacheKey = "exchange:idem-1:" + USER;
        QuoteData quote = new QuoteData("quote-1", USER, ExchangeType.EXCHANGE,
                CurrencyType.KRW, CurrencyType.USD, new BigDecimal("100000"),
                new BigDecimal("1380"), new BigDecimal("500"), CurrencyType.KRW,
                new BigDecimal("72.1014"), CurrencyType.USD);
        ExchangeResponse priorResponse = ExchangeResponse.builder().publicId("ex-prior").status("COMPLETED").build();
        when(idempotencyCacheHelper.get(cacheKey)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(quoteRedisRepository.find("quote-1")).thenReturn(Optional.of(quote));            // 소유자 확인
        when(quoteRedisRepository.getAndDelete("quote-1")).thenReturn(Optional.of(quote));    // 원자 소비 성공
        when(self.executeInTransaction(USER, "idem-1", quote))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency_key"));
        when(self.readPrior(USER, "idem-1")).thenReturn(priorResponse);

        ExchangeResponse result = exchangeService.execute(USER, "idem-1", request);

        assertThat(result).isSameAs(priorResponse);
        verify(self).readPrior(USER, "idem-1"); // 소유자 스코프로 재조회(EX-FIX)
        verify(quoteRedisRepository, never()).delete(any()); // best-effort delete 경로 폐기
    }

    // ───────────────────── EXB1: 락 경합 재시도(충전/송금과 대칭) ─────────────────────

    @Test
    @DisplayName("실행 락경합: executeInTransaction이 PessimisticLockingFailureException 1회 던지면 재시도해 성공 반환")
    void execute_락경합_재시도_성공() throws Exception {
        ReflectionTestUtils.setField(exchangeService, "self", self); // self-proxy 위임 검증용
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        String cacheKey = "exchange:idem-1:" + USER;
        QuoteData quote = new QuoteData("quote-1", USER, ExchangeType.EXCHANGE,
                CurrencyType.KRW, CurrencyType.USD, new BigDecimal("100000"),
                new BigDecimal("1380"), new BigDecimal("500"), CurrencyType.KRW,
                new BigDecimal("72.1014"), CurrencyType.USD);
        ExchangeResponse response = ExchangeResponse.builder().publicId("ex-1").status("COMPLETED").build();
        when(idempotencyCacheHelper.get(cacheKey)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(quoteRedisRepository.find("quote-1")).thenReturn(Optional.of(quote));
        when(quoteRedisRepository.getAndDelete("quote-1")).thenReturn(Optional.of(quote)); // 원자 소비는 1회만
        // 1차: 락 경합으로 롤백(CannotAcquireLockException ⊂ PessimisticLockingFailureException) → 2차: 성공
        when(self.executeInTransaction(USER, "idem-1", quote))
                .thenThrow(new CannotAcquireLockException("lock timeout"))
                .thenReturn(response);
        when(objectMapper.writeValueAsString(response)).thenReturn("{\"json\":\"ok\"}");

        ExchangeResponse result = exchangeService.execute(USER, "idem-1", request);

        assertThat(result).isSameAs(response);
        verify(self, times(2)).executeInTransaction(USER, "idem-1", quote); // 1회 실패 + 1회 성공
        verify(self, never()).readPrior(any(), any());                      // 락경합은 readPrior가 아닌 재시도로 복구
        verify(quoteRedisRepository, times(1)).getAndDelete("quote-1");      // 재시도해도 견적 재소비 없음(consumed 재사용)
        verify(idempotencyCacheHelper).set(cacheKey, "{\"json\":\"ok\"}");   // 성공 응답은 캐시에 저장
    }

    @Test
    @DisplayName("실행 락경합: 재시도(최대 3회) 모두 실패하면 COMMON5031(503), 캐시 미저장")
    void execute_락경합_재시도소진_COMMON5031() {
        ReflectionTestUtils.setField(exchangeService, "self", self);
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        String cacheKey = "exchange:idem-1:" + USER;
        QuoteData quote = new QuoteData("quote-1", USER, ExchangeType.EXCHANGE,
                CurrencyType.KRW, CurrencyType.USD, new BigDecimal("100000"),
                new BigDecimal("1380"), new BigDecimal("500"), CurrencyType.KRW,
                new BigDecimal("72.1014"), CurrencyType.USD);
        when(idempotencyCacheHelper.get(cacheKey)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(quoteRedisRepository.find("quote-1")).thenReturn(Optional.of(quote));
        when(quoteRedisRepository.getAndDelete("quote-1")).thenReturn(Optional.of(quote));
        when(self.executeInTransaction(USER, "idem-1", quote))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> exchangeService.execute(USER, "idem-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(self, times(3)).executeInTransaction(USER, "idem-1", quote); // MAX_EXCHANGE_ATTEMPTS
        verify(self, never()).readPrior(any(), any());
        verify(idempotencyCacheHelper, never()).set(any(), any()); // 에러 응답은 캐시에 넣지 않는다
    }

    // ───────────────────── WU-L1: 견적 원자 소비(이중 환전 차단) ─────────────────────

    @Test
    @DisplayName("WEXB-01: 견적 원자 소비 경쟁에서 진 쪽(getAndDelete empty) → QUOTE_EXPIRED, 실행/거래저장 0(이중 환전 차단)")
    void execute_견적_원자소비_경쟁_진쪽_차단() {
        ReflectionTestUtils.setField(exchangeService, "self", self);
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        QuoteData quote = new QuoteData("quote-1", USER, ExchangeType.EXCHANGE,
                CurrencyType.KRW, CurrencyType.USD, new BigDecimal("100000"),
                new BigDecimal("1380"), new BigDecimal("500"), CurrencyType.KRW,
                new BigDecimal("72.1014"), CurrencyType.USD);
        // 같은 견적을 다른 idempotency_key로 동시 요청한 상황: 소유자 확인 시점(find)엔 견적이 보이지만,
        // 다른 요청이 먼저 원자 소비(getAndDelete)해 이 요청은 empty를 받는다 → 실행 진입 불가.
        when(transactionRepository.findByIdempotencyKey("idem-2")).thenReturn(Optional.empty());
        when(quoteRedisRepository.find("quote-1")).thenReturn(Optional.of(quote));
        when(quoteRedisRepository.getAndDelete("quote-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeService.execute(USER, "idem-2", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ExchangeErrorCode.QUOTE_EXPIRED);

        // 견적을 획득 못 했으니 실행·거래 저장이 일어나지 않는다(두 번째 환전 차단).
        verify(self, never()).executeInTransaction(any(), any(), any());
        verify(transactionRepository, never()).save(any());
    }

    // ───────────────────── EX-T2: 멱등 재반환 cross-user/cross-type 차단(EX-FIX 회귀) ─────────────────────

    @Test
    @DisplayName("실행 Layer 2: 타인의 idempotency_key 거래가 잡히면 EXCHANGE4001로 차단(남의 거래 미노출)")
    void execute_Layer2_타인키_차단() {
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        String cacheKey = "exchange:idem-1:" + USER;
        // 같은 idempotency_key(전역 UNIQUE)지만 거래 주인은 다른 사용자
        Wallet othersWallet = Wallet.builder()
                .publicId("w-2").userPublicId("other-user").status(WalletStatus.ACTIVE).build();
        Transaction othersTx = exchangeTx(othersWallet, "ex-others");
        when(idempotencyCacheHelper.get(cacheKey)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(othersTx));

        assertThatThrownBy(() -> exchangeService.execute(USER, "idem-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ExchangeErrorCode.EXCHANGE_NOT_FOUND);

        // 남의 거래를 재반환하지도, 새 거래를 만들지도 않는다.
        verify(quoteRedisRepository, never()).find(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("실행 Layer 2: 같은 키가 본인의 비-EXCHANGE(CHARGE) 거래면 EXCHANGE4001로 차단(유형 교차 방지)")
    void execute_Layer2_타유형키_차단() {
        ExchangeExecuteRequest request = new ExchangeExecuteRequest();
        ReflectionTestUtils.setField(request, "quotePublicId", "quote-1");
        String cacheKey = "exchange:idem-1:" + USER;
        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        // 본인 거래지만 유형이 CHARGE — 환전 응답으로 재현하면 안 됨
        Transaction charge = Transaction.builder()
                .publicId("ch-1").wallet(wallet).type(TransactionType.CHARGE)
                .amount(new BigDecimal("100000")).currencyCode(CurrencyType.KRW)
                .fee(BigDecimal.ZERO).status(TransactionStatus.COMPLETED).idempotencyKey("idem-1").build();
        when(idempotencyCacheHelper.get(cacheKey)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(charge));

        assertThatThrownBy(() -> exchangeService.execute(USER, "idem-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ExchangeErrorCode.EXCHANGE_NOT_FOUND);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("readPrior(Layer 3): 재조회한 거래가 타인 것이면 EXCHANGE4001로 차단")
    void readPrior_타인거래_차단() {
        Wallet othersWallet = Wallet.builder()
                .publicId("w-2").userPublicId("other-user").status(WalletStatus.ACTIVE).build();
        Transaction othersTx = exchangeTx(othersWallet, "ex-others");
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(othersTx));

        assertThatThrownBy(() -> exchangeService.readPrior(USER, "idem-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ExchangeErrorCode.EXCHANGE_NOT_FOUND);
    }

    @Test
    @DisplayName("readPrior(Layer 3): 본인 EXCHANGE 거래면 정상 재반환")
    void readPrior_본인거래_재반환() {
        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Transaction prior = exchangeTx(wallet, "ex-prior");
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(prior));

        ExchangeResponse result = exchangeService.readPrior(USER, "idem-1");

        assertThat(result.getPublicId()).isEqualTo("ex-prior");
        assertThat(result.getExchangeType()).isEqualTo("EXCHANGE"); // 수령=USD → 역산
    }

    // ───────────────────── EX-T4: 내역 단건(getExchange) ─────────────────────

    @Test
    @DisplayName("내역 단건: 본인 EXCHANGE 거래면 응답으로 변환(수령 통화 KRW면 RE_EXCHANGE로 역산)")
    void getExchange_본인_재환전_역산() {
        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Transaction tx = Transaction.builder()
                .publicId("ex-1").wallet(wallet).type(TransactionType.EXCHANGE)
                .amount(new BigDecimal("100")).currencyCode(CurrencyType.USD)
                .fee(new BigDecimal("690")).status(TransactionStatus.COMPLETED)
                .idempotencyKey("k").receiveAmount(new BigDecimal("137310"))
                .receiveCurrencyCode(CurrencyType.KRW).exchangeRate(new BigDecimal("1380"))
                .toAmount(new BigDecimal("137310")).build();
        when(transactionRepository.findByPublicId("ex-1")).thenReturn(Optional.of(tx));

        ExchangeResponse response = exchangeService.getExchange(USER, "ex-1");

        assertThat(response.getPublicId()).isEqualTo("ex-1");
        assertThat(response.getExchangeType()).isEqualTo("RE_EXCHANGE"); // 수령=KRW → 역산
        assertThat(response.getFromCurrencyCode()).isEqualTo("USD");
        assertThat(response.getToCurrencyCode()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("내역 단건: 없는 id면 EXCHANGE4001")
    void getExchange_없음() {
        when(transactionRepository.findByPublicId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeService.getExchange(USER, "nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ExchangeErrorCode.EXCHANGE_NOT_FOUND);
    }

    @Test
    @DisplayName("내역 단건: EXCHANGE 타입이 아니면(CHARGE 등) EXCHANGE4001 (존재 미노출)")
    void getExchange_비환전타입() {
        Wallet wallet = Wallet.builder().publicId("w-1").userPublicId(USER).status(WalletStatus.ACTIVE).build();
        Transaction charge = Transaction.builder()
                .publicId("ch-1").wallet(wallet).type(TransactionType.CHARGE)
                .amount(new BigDecimal("100000")).currencyCode(CurrencyType.KRW)
                .fee(BigDecimal.ZERO).status(TransactionStatus.COMPLETED).idempotencyKey("k").build();
        when(transactionRepository.findByPublicId("ch-1")).thenReturn(Optional.of(charge));

        assertThatThrownBy(() -> exchangeService.getExchange(USER, "ch-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ExchangeErrorCode.EXCHANGE_NOT_FOUND);
    }

    @Test
    @DisplayName("내역 단건: 타인의 환전 거래면 COMMON4031")
    void getExchange_타인거래_거절() {
        Wallet othersWallet = Wallet.builder()
                .publicId("w-2").userPublicId("other-user").status(WalletStatus.ACTIVE).build();
        Transaction tx = Transaction.builder()
                .publicId("ex-9").wallet(othersWallet).type(TransactionType.EXCHANGE)
                .amount(new BigDecimal("100000")).currencyCode(CurrencyType.KRW)
                .fee(new BigDecimal("500")).status(TransactionStatus.COMPLETED).idempotencyKey("k")
                .receiveAmount(new BigDecimal("72")).receiveCurrencyCode(CurrencyType.USD)
                .exchangeRate(new BigDecimal("1380")).toAmount(new BigDecimal("72")).build();
        when(transactionRepository.findByPublicId("ex-9")).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> exchangeService.getExchange(USER, "ex-9"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    // ----- helpers -----

    /** toResponse가 읽는 필수 필드를 채운 본인(EXCHANGE, 수령=USD) 거래. 멱등 재반환 경로 검증용. */
    private static Transaction exchangeTx(Wallet wallet, String publicId) {
        return Transaction.builder()
                .publicId(publicId).wallet(wallet).type(TransactionType.EXCHANGE)
                .amount(new BigDecimal("100000")).currencyCode(CurrencyType.KRW)
                .fee(new BigDecimal("500")).status(TransactionStatus.COMPLETED)
                .idempotencyKey("idem-1").receiveAmount(new BigDecimal("72.1014"))
                .receiveCurrencyCode(CurrencyType.USD).exchangeRate(new BigDecimal("1380"))
                .toAmount(new BigDecimal("72.1014")).build();
    }
}
