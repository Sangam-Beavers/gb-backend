package com.gb.wallet.domain.exchange.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.gb.wallet.domain.exchange.repository.QuoteRedisRepository;
import com.gb.wallet.domain.exchange.service.ExchangeService;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
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
import com.gb.wallet.global.exception.code.ExchangeErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.IdempotencyCacheHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeServiceImpl implements ExchangeService {

    /** 환전 수수료: 신청 금액의 0.5%(KRW 기준), 소수 4자리 HALF_UP. 임시 정책 — 운영 시 정책 객체로 분리. */
    private static final BigDecimal EXCHANGE_FEE_RATE = new BigDecimal("0.005");
    private static final int MONEY_SCALE = 4;
    private static final int RATE_SCALE = 8;
    /** 견적 유효 시간(분). Redis TTL과 응답 expires_at 계산에 함께 사용. */
    private static final long QUOTE_TTL_MINUTES = 5L;
    private static final String EXCHANGE_ACTION = "EXCHANGE";

    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final WalletBalanceWriter walletBalanceWriter;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditLogRepository auditLogRepository;
    private final QuoteRedisRepository quoteRedisRepository;
    private final ExchangeRateClient exchangeRateClient;
    private final IdempotencyCacheHelper idempotencyCacheHelper;
    private final ObjectMapper objectMapper;

    /** self-injection: @Transactional 프록시 적용 위함(충전/송금 동일 패턴). */
    @Autowired
    @Lazy
    private ExchangeService self;

    @Override
    @Transactional(readOnly = true)
    public SupportedCurrenciesResponse getSupportedCurrencies() {
        // 지원 통화는 CurrencyType enum이 SSOT — DB 조회 없이 enum에서 구성한다.
        return SupportedCurrenciesResponse.of();
    }

    // ───────────────────────────── 견적 ─────────────────────────────

    @Override
    public QuoteResponse createQuote(String userPublicId, QuoteRequest request) {
        // 1) 입력 검증 — 형식은 @Valid에서, enum/통화는 여기서(도메인 에러로 매핑).
        ExchangeType exchangeType = parseExchangeType(request.getExchangeType());
        CurrencyType from = parseCurrency(request.getFromCurrencyCode());
        CurrencyType to = parseCurrency(request.getToCurrencyCode());
        BigDecimal amount = parseAmount(request.getAmount());

        // 통화 조합 검증 — 환전은 "원화↔외화"만 허용. 같은 통화(KRW→KRW)나 외화↔외화(USD→PHP)는 거부.
        // exactly one이 KRW여야 한다(둘 다 KRW거나 둘 다 외화면 미지원 조합).
        boolean exactlyOneKrw = (from == CurrencyType.KRW) ^ (to == CurrencyType.KRW);
        if (!exactlyOneKrw) {
            throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY);
        }

        // 2) 환율 계산. 견적은 "1 외화 → KRW" 환율을 기준으로 from→to 환산.
        //    EXCHANGE(원화→외화): KRW amount → 외화. RE_EXCHANGE(외화→원화): 외화 amount → KRW.
        BigDecimal fromRate = rateToKrw(from);   // 1 from = ?KRW
        BigDecimal toRate = rateToKrw(to);       // 1 to   = ?KRW

        // amount(from 통화) → KRW 환산 → to 통화로 환산.
        BigDecimal amountInKrw = amount.multiply(fromRate);
        // 수수료는 KRW 기준 0.5%.
        BigDecimal fee = amountInKrw.multiply(EXCHANGE_FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // 수수료 차감 후 KRW를 to 통화로 환산 = 수령액.
        BigDecimal receiveAmount = amountInKrw.subtract(fee)
                .divide(toRate, MONEY_SCALE, RoundingMode.HALF_UP);

        // 응답 환율은 명세상 "1 외화 → KRW". EXCHANGE면 to(외화) 환율, RE_EXCHANGE면 from(외화) 환율을 노출.
        BigDecimal displayRate = (exchangeType == ExchangeType.EXCHANGE ? toRate : fromRate)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);

        // 3) 견적 스냅샷 생성 + Redis 저장(TTL 5분).
        String quotePublicId = UUID.randomUUID().toString();
        QuoteData quote = new QuoteData(
                quotePublicId, userPublicId, exchangeType, from, to,
                amount, displayRate, fee, CurrencyType.KRW, receiveAmount, to);
        quoteRedisRepository.save(quote);

        Instant expiresAt = Instant.now().plus(QUOTE_TTL_MINUTES, ChronoUnit.MINUTES);
        return QuoteResponse.of(quote, expiresAt);
    }

    // ───────────────────────────── 실행 ─────────────────────────────

    @Override
    public ExchangeResponse execute(String userPublicId, String idempotencyKey,
                                    ExchangeExecuteRequest request) {
        // 멱등성 Layer 1 — Redis 캐시(가장 빠른 경로). 키를 (요청자, 환전 도메인)으로 스코프해, 같은
        // idempotency_key라도 다른 사용자는 캐시 hit이 나지 않고(교차 사용자 격리), 송금/충전과 prefix를
        // 공유하는 멱등 캐시에서 교차 도메인 역직렬화도 차단한다(charge와 동일 패턴). 캐시 실패는 응답을 막지
        // 않는다 — Layer 2(DB findByIdempotencyKey)·3(UNIQUE race-catch)가 안전망.
        String cacheKey = cacheKey(idempotencyKey, userPublicId);
        Optional<ExchangeResponse> cached = readFromCache(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        ExchangeResponse response = doExecute(userPublicId, idempotencyKey, request);

        // 정상/race 복구 응답을 Layer 1에 채운다. 에러(BusinessException)는 doExecute에서 전파돼 캐시에 안 들어간다.
        writeToCache(cacheKey, response);
        return response;
    }

    private ExchangeResponse doExecute(String userPublicId, String idempotencyKey,
                                       ExchangeExecuteRequest request) {
        // Layer 2 — 이미 처리된 키면 첫 거래를 재반환(잔액 재변경 없음).
        //   idempotency_key는 전역 UNIQUE(도메인·사용자 무관)라 타 사용자/유형 거래가 잡힐 수 있으므로,
        //   요청자 본인의 EXCHANGE 거래인지 검증 후 재반환한다(충전 rebuildFromPrior와 동일 — 교차 노출 차단).
        Optional<Transaction> prior = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) {
            return rebuildPrior(prior.get(), userPublicId);
        }

        // 견적 조회 — 없으면(TTL 만료/미존재) 만료 처리.
        QuoteData quote = quoteRedisRepository.find(request.getQuotePublicId())
                .orElseThrow(() -> new BusinessException(ExchangeErrorCode.QUOTE_EXPIRED));

        // 견적 발급자 본인 확인 — 타인이 견적 id를 탈취해 실행하는 것 방지.
        if (!quote.userPublicId().equals(userPublicId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 트랜잭션 내 실행 (self-proxy — 직접 호출 시 @Transactional 미적용).
        try {
            ExchangeResponse response = self.executeInTransaction(userPublicId, idempotencyKey, quote);
            quoteRedisRepository.delete(quote.quotePublicId()); // 성공 시 견적 삭제(재사용 방지)
            return response;
        } catch (DataIntegrityViolationException race) {
            // Layer 3 — 동시 race로 같은 키가 먼저 커밋됨 → 첫 거래 재조회(소유자·유형 검증 포함).
            return self.readPrior(userPublicId, idempotencyKey);
        }
    }

    /**
     * 환전 멱등성 Layer 1 캐시 키. {@code idempotency_key}를 (요청자, 환전 도메인) 단위로 스코프한다.
     * {@code IdempotencyCacheHelper}가 {@code idempotency:} prefix를 덧붙이므로 최종 키는
     * {@code idempotency:exchange:{key}:{user}}다.
     */
    private static String cacheKey(String idempotencyKey, String userPublicId) {
        return "exchange:" + idempotencyKey + ":" + userPublicId;
    }

    private Optional<ExchangeResponse> readFromCache(String cacheKey) {
        try {
            return idempotencyCacheHelper.get(cacheKey).flatMap(json -> {
                try {
                    return Optional.of(objectMapper.readValue(json, ExchangeResponse.class));
                } catch (JsonProcessingException e) {
                    log.warn("환전 멱등 캐시 역직렬화 실패 — Layer 2로 폴백. key={}", cacheKey, e);
                    return Optional.empty();
                }
            });
        } catch (RuntimeException e) {
            log.warn("환전 멱등 캐시 조회 실패 — Layer 2로 폴백. key={}", cacheKey, e);
            return Optional.empty();
        }
    }

    private void writeToCache(String cacheKey, ExchangeResponse response) {
        try {
            idempotencyCacheHelper.set(cacheKey, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("환전 멱등 캐시 저장 실패. key={}", cacheKey, e);
        }
    }

    @Override
    @Transactional
    public ExchangeResponse executeInTransaction(String userPublicId, String idempotencyKey, QuoteData quote) {
        // (1) 지갑 조회.
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // (2) 받을 통화(to) 잔액 행 0원 보장 — 없으면 FOR UPDATE로 못 잠그므로 먼저 생성(REQUIRES_NEW).
        walletBalanceWriter.ensureBalanceRow(wallet, quote.toCurrencyCode());

        // (3) from/to 잔액 행을 비관적 락으로 조회. 같은 지갑 두 통화라 통화명 오름차순으로 잠가 데드락을 피한다.
        //     단일 wallet이라 교차-wallet 데드락 표면이 없어 Redis 분산락은 두지 않는다(분산락은 두 wallet을
        //     다루는 INTERNAL_TRANSFER 전용 — api-spec §6-2). DB 비관락 + idempotency_key UNIQUE로 충분.
        boolean fromFirst = quote.fromCurrencyCode().name().compareTo(quote.toCurrencyCode().name()) <= 0;
        CurrencyType firstCur = fromFirst ? quote.fromCurrencyCode() : quote.toCurrencyCode();
        CurrencyType secondCur = fromFirst ? quote.toCurrencyCode() : quote.fromCurrencyCode();
        walletBalanceRepository.findForUpdateByWalletAndCurrency(wallet, firstCur);
        walletBalanceRepository.findForUpdateByWalletAndCurrency(wallet, secondCur);

        // 잠근 뒤 역할별로 다시 가져온다(from = 출금, to = 입금).
        WalletBalance fromBalance = walletBalanceRepository
                .findForUpdateByWalletAndCurrency(wallet, quote.fromCurrencyCode())
                // from 잔액 행이 없다 = 바꿀 돈이 없음 → 잔액 부족.
                .orElseThrow(() -> new BusinessException(WalletErrorCode.INSUFFICIENT_BALANCE));
        WalletBalance toBalance = walletBalanceRepository
                .findForUpdateByWalletAndCurrency(wallet, quote.toCurrencyCode())
                // to 행은 (2)에서 보장했으므로 없으면 정합성 깨진 비정상 상태.
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));

        // (4) 잔액 검증 — 신청 금액(from 통화)만큼 있어야 함. 부족하면 WALLET4002.
        if (fromBalance.getBalance().compareTo(quote.amount()) < 0) {
            throw new BusinessException(WalletErrorCode.INSUFFICIENT_BALANCE);
        }

        // (5) 잔액 변경 — from 차감, to 증가.
        BigDecimal fromBefore = fromBalance.getBalance();
        BigDecimal toBefore = toBalance.getBalance();
        fromBalance.subtract(quote.amount());
        toBalance.addBalance(quote.receiveAmount());
        BigDecimal fromAfter = fromBalance.getBalance();
        BigDecimal toAfter = toBalance.getBalance();

        // (6) 거래 기록 INSERT (type=EXCHANGE). idempotency_key UNIQUE 위반 시 catch로 race 처리.
        Transaction tx = transactionRepository.save(Transaction.builder()
                .publicId(UUID.randomUUID().toString())
                .wallet(wallet)
                .type(TransactionType.EXCHANGE)
                .amount(quote.amount())
                .currencyCode(quote.fromCurrencyCode())     // 출금 통화
                .fee(quote.fee())
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(idempotencyKey)
                .receiveAmount(quote.receiveAmount())
                .receiveCurrencyCode(quote.toCurrencyCode())
                .exchangeRate(quote.exchangeRate())
                .toAmount(quote.receiveAmount())
                .build());

        // (7) 감사 로그 INSERT × 2 (출금/입금, append-only).
        auditLogRepository.save(TransactionAuditLog.builder()
                .transaction(tx)
                .userPublicId(userPublicId)
                .action(EXCHANGE_ACTION + "_FROM")
                .amount(quote.amount())
                .currencyCode(quote.fromCurrencyCode())
                .beforeBalance(fromBefore)
                .afterBalance(fromAfter)
                .status(TransactionStatus.COMPLETED)
                .build());
        auditLogRepository.save(TransactionAuditLog.builder()
                .transaction(tx)
                .userPublicId(userPublicId)
                .action(EXCHANGE_ACTION + "_TO")
                .amount(quote.receiveAmount())
                .currencyCode(quote.toCurrencyCode())
                .beforeBalance(toBefore)
                .afterBalance(toAfter)
                .status(TransactionStatus.COMPLETED)
                .build());

        return ExchangeResponse.from(tx, quote.exchangeType().name());
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeResponse readPrior(String userPublicId, String idempotencyKey) {
        Transaction prior = transactionRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        return rebuildPrior(prior, userPublicId);
    }

    // ───────────────────────────── 내역 조회 ─────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ExchangeResponse getExchange(String userPublicId, String exchangePublicId) {
        Transaction tx = transactionRepository.findByPublicId(exchangePublicId)
                // 없거나 환전 거래가 아니면 EXCHANGE4001.
                .filter(t -> t.getType() == TransactionType.EXCHANGE)
                .orElseThrow(() -> new BusinessException(ExchangeErrorCode.EXCHANGE_NOT_FOUND));

        // 본인 것이 아니면 COMMON4031.
        if (!tx.getWallet().getUserPublicId().equals(userPublicId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return toResponse(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeListResponse getExchanges(String userPublicId, int page, int size) {
        // 본인 환전(type=EXCHANGE) 거래를 최근순으로 페이지 조회. 잔액이 아니라 거래 이력이라 캐시하지 않는다.
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Transaction> result = transactionRepository
                .findByWallet_UserPublicIdAndType(userPublicId, TransactionType.EXCHANGE, pageable);
        List<ExchangeResponse> items = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        return ExchangeListResponse.of(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    // ───────────────────────────── 헬퍼 ─────────────────────────────

    /**
     * 멱등 재반환(Layer 2/3) 전용 검증. {@code idempotency_key}는 전역 UNIQUE라 같은 키로 다른 사용자/유형의
     * 거래가 잡힐 수 있으므로, 이 환전 요청의 응답으로 재현해도 되는 거래인지 확인하고 아니면 EXCHANGE4001로
     * 차단한다(존재 미노출 — 충전 {@code rebuildFromPrior}와 동일 정책). 단건 조회({@link #getExchange})가 이미
     * 적용 중인 "본인 + EXCHANGE" 검증을 멱등 경로에도 동일하게 적용해 교차 사용자/유형 노출을 막는다.
     */
    private ExchangeResponse rebuildPrior(Transaction prior, String userPublicId) {
        if (!prior.getWallet().getUserPublicId().equals(userPublicId)
                || prior.getType() != TransactionType.EXCHANGE) {
            throw new BusinessException(ExchangeErrorCode.EXCHANGE_NOT_FOUND);
        }
        return toResponse(prior);
    }

    /** 멱등성 재반환·내역 조회 공용. transactions에 환전 유형 컬럼이 없어 from/to 통화로 유형을 역산한다. */
    private ExchangeResponse toResponse(Transaction tx) {
        ExchangeType type = (tx.getReceiveCurrencyCode() == CurrencyType.KRW)
                ? ExchangeType.RE_EXCHANGE : ExchangeType.EXCHANGE;
        return ExchangeResponse.from(tx, type.name());
    }

    private ExchangeType parseExchangeType(String value) {
        try {
            return ExchangeType.valueOf(value);
        } catch (IllegalArgumentException e) {
            // 잘못된 환전 유형은 요청 값 오류로 처리.
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    private CurrencyType parseCurrency(String code) {
        return CurrencyType.fromCode(code)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));
    }

    private BigDecimal parseAmount(String amount) {
        try {
            BigDecimal value = new BigDecimal(amount);
            if (value.signum() <= 0) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    /** 환율표에 없거나(미지원) 0 이하인 비정상 환율이면 미지원 통화(TRANSFER4002). */
    private BigDecimal rateToKrw(CurrencyType currency) {
        BigDecimal rate = exchangeRateClient.getRateToKrw(currency);
        // null(미지원) 또는 0 이하(비정상)면 거부 — 0 이하면 이후 나눗셈/계산이 깨진다.
        if (rate == null || rate.signum() <= 0) {
            throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY);
        }
        return rate;
    }
}
