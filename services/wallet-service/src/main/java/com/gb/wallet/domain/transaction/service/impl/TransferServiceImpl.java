package com.gb.wallet.domain.transaction.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.dto.request.TransferFeeRequest;
import com.gb.wallet.domain.transaction.dto.request.ValidateScheduledRequest;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse.AccountItem;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse.RecipientItem;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse.CurrencyItem;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferFeeResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferReceiptResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateScheduledResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.ReceiverCurrencyProjection;
import com.gb.wallet.domain.transaction.repository.RecentAccountProjection;
import com.gb.wallet.domain.transaction.repository.RecentRecipientProjection;
import com.gb.wallet.domain.transaction.repository.RemittanceAmountProjection;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.RemittanceAttemptWriter;
import com.gb.wallet.domain.transaction.service.TransferPinGate;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.domain.wallet.service.WalletBalanceWriter;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.common.util.AccountNumberMasker;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.MemberErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.config.TransferRateLimitProperties;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.DistributedLockHelper;
import com.gb.wallet.global.redis.IdempotencyCacheHelper;
import com.gb.wallet.global.redis.RateLimitHelper;

import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferServiceImpl implements TransferService {

    /**
     * 명세상 고정 10명. 페이지네이션 없음.
     */
    private static final int RECENT_LIMIT = 10;

    // 송금 수수료 정책 상수.
    // TODO: 수수료 정책 확정 시 정책 테이블/외부 조회로 교체. 현재 0.5%는 임시 값
    //       (docs/remittance/api-spec.md §4 참고). 정책 SSOT가 docs라 코드 상수 동기화 주의.
    private static final BigDecimal REMITTANCE_FEE_RATE = new BigDecimal("0.005");
    private static final int FEE_SCALE = 4;

    /**
     * 수수료 API가 허용하는 송금 유형. TransactionType 중 CHARGE/EXCHANGE는 거부.
     */
    private static final Set<TransactionType> ALLOWED_TRANSFER_TYPES =
            EnumSet.of(TransactionType.INTERNAL_TRANSFER, TransactionType.REMITTANCE);

    /**
     * 송금 락 경합(데드락·락 타임아웃)으로 트랜잭션이 롤백됐을 때 executeInTransaction 재시도 최대 횟수.
     * 소진 시 COMMON5031(503). 충전 {@code ChargeServiceImpl.MAX_CHARGE_ATTEMPTS} 패턴 답습.
     */
    private static final int MAX_TRANSFER_ATTEMPTS = 3;

    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final WalletBalanceWriter walletBalanceWriter;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditLogRepository auditLogRepository;
    private final RemittanceAttemptWriter remittanceAttemptWriter;
    private final BankAccountRepository bankAccountRepository;
    private final MemberClient memberClient;
    private final BankClient bankClient;
    private final DistributedLockHelper distributedLockHelper;
    private final IdempotencyCacheHelper idempotencyCacheHelper;
    private final RateLimitHelper rateLimitHelper;
    private final TransferRateLimitProperties transferRateLimitProperties;
    private final TransferPinGate transferPinGate;
    private final ObjectMapper objectMapper;

    /**
     * 송금 rate-limit 카운터 키 prefix(user 단위).
     */
    private static final String TRANSFER_RATE_LIMIT_PREFIX = "ratelimit:transfer:";

    /**
     * self-injection: {@code @Transactional}이 적용되려면 {@link #executeInTransaction}/{@link #readPriorTransaction}을
     * AOP 프록시를 통해 호출해야 한다(같은 빈 내부의 {@code this.executeInTransaction()}은 프록시를 우회해
     * 트랜잭션이 안 걸림). {@code @Lazy}로 빈 생성 시점의 자기참조 순환을 끊는다.
     *
     * <p>필드 주입을 쓴 이유는 ChargeServiceImpl의 동일 패턴 javadoc 참고 — 프로젝트에 {@code lombok.config}가
     * 없어 {@code @RequiredArgsConstructor}가 {@code @Lazy}를 생성자 파라미터로 복사하지 않는다.
     */
    @Autowired
    @Lazy
    private TransferService self;

    @Override
    public RecentRecipientsResponse getRecentInternalRecipients(String userPublicId) {
        // 1) 송신자 wallet 조회. 없으면 WALLET4001.
        Wallet sender = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // 2) 수신자별 최신 송금 1건씩, 최근순 10건.
        List<RecentRecipientProjection> recent = transactionRepository
                .findRecentInternalTransferRecipients(sender.getId(), PageRequest.of(0, RECENT_LIMIT));

        if (recent.isEmpty()) {
            return RecentRecipientsResponse.of(List.of());
        }

        List<Long> receiverIds = recent.stream()
                .map(RecentRecipientProjection::getReceiverWalletId)
                .toList();
        List<LocalDateTime> timestamps = recent.stream()
                .map(RecentRecipientProjection::getLastTransferredAt)
                .toList();

        // 3) (receiverWalletId, lastTransferredAt) → currency_code IN-batch 1회.
        //    서로 다른 receiver가 같은 timestamp를 갖는 희박한 충돌은 정확 매칭으로 한 번 더 검증.
        Map<Long, LocalDateTime> expectedTimestamp = recent.stream().collect(Collectors.toMap(
                RecentRecipientProjection::getReceiverWalletId,
                RecentRecipientProjection::getLastTransferredAt));
        Map<Long, CurrencyType> currencyByReceiver = new HashMap<>();
        for (ReceiverCurrencyProjection row : transactionRepository
                .findCurrencyCodesForLatestTransfers(sender.getId(), receiverIds, timestamps)) {
            if (Objects.equals(expectedTimestamp.get(row.getReceiverWalletId()), row.getCreatedAt())) {
                currencyByReceiver.putIfAbsent(row.getReceiverWalletId(), row.getCurrencyCode());
            }
        }

        // 4) receiverWalletId → user_public_id 매핑. JpaRepository 내장 findAllById(IN-batch) 사용.
        Map<Long, String> userPublicIdByWallet = walletRepository.findAllById(receiverIds).stream()
                .collect(Collectors.toMap(Wallet::getId, Wallet::getUserPublicId));

        // 5) 각 수신자에 대해 MemberClient 호출 → RecipientItem 변환. projection 순서(최근순) 유지.
        // TODO: member-service 도입 시 N번 호출은 batch API(예: GET /members?ids=...)로 최적화.
        List<RecipientItem> items = recent.stream()
                .map(p -> {
                    String receiverUserId = userPublicIdByWallet.get(p.getReceiverWalletId());
                    MemberInfo member = memberClient.getMember(receiverUserId);
                    CurrencyType lastCurrency = currencyByReceiver.get(p.getReceiverWalletId());
                    return RecipientItem.builder()
                            .memberPublicId(receiverUserId)
                            .nickname(member.nickname())
                            .nationality(member.nationality())
                            .isVerified(member.isVerified())
                            .lastCurrencyCode(lastCurrency != null ? lastCurrency.name() : null)
                            .lastTransferredAt(RecentRecipientsResponse.toUtcZ(p.getLastTransferredAt()))
                            .build();
                })
                .toList();

        return RecentRecipientsResponse.of(items);
    }

    @Override
    public ValidateMemberResponse validateMember(String email) {
        MemberInfo member = memberClient.findByEmail(email)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        return ValidateMemberResponse.from(member);
    }

    @Override
    public SupportedCurrenciesResponse getSupportedCurrencies() {
        // CurrencyType enum이 SSOT. DB/외부 호출 없이 enum 순회로 응답 구성.
        // @Transactional이 굳이 필요 없는 순수 조회라 어노테이션을 붙이지 않는다 (클래스 레벨 readOnly도 영향 없음).
        List<CurrencyItem> items = Arrays.stream(CurrencyType.values())
                .map(CurrencyItem::from)
                .toList();
        return SupportedCurrenciesResponse.of(items);
    }

    @Override
    public RecentAccountsResponse getRecentRemittanceAccounts(String userPublicId, int size) {
        // 1) 송신자 wallet 조회. 없으면 WALLET4001.
        Wallet sender = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // 2) bank_account별 최신 송금 1건씩, 최근순 N건.
        List<RecentAccountProjection> recent = transactionRepository
                .findRecentRemittanceAccounts(sender.getId(), PageRequest.of(0, size));

        if (recent.isEmpty()) {
            return RecentAccountsResponse.of(List.of());
        }

        List<Long> bankAccountIds = recent.stream()
                .map(RecentAccountProjection::getBankAccountId)
                .toList();
        List<LocalDateTime> timestamps = recent.stream()
                .map(RecentAccountProjection::getLastTransferredAt)
                .toList();

        // 3) (bankAccountId, lastTransferredAt) → amount/currency/receiverName IN-batch 1회.
        Map<Long, LocalDateTime> expectedTimestamp = recent.stream().collect(Collectors.toMap(
                RecentAccountProjection::getBankAccountId,
                RecentAccountProjection::getLastTransferredAt));
        Map<Long, RemittanceAmountProjection> lastByBankAccount = new HashMap<>();
        for (RemittanceAmountProjection row : transactionRepository
                .findAmountsForLatestRemittances(sender.getId(), bankAccountIds, timestamps)) {
            if (Objects.equals(expectedTimestamp.get(row.getBankAccountId()), row.getCreatedAt())) {
                lastByBankAccount.putIfAbsent(row.getBankAccountId(), row);
            }
        }

        // 4) BankAccount IN-batch (bank ManyToOne을 EntityGraph로 eager fetch — N+1 방지).
        Map<Long, BankAccount> accountById = bankAccountRepository.findAllByIdIn(bankAccountIds).stream()
                .collect(Collectors.toMap(BankAccount::getId, a -> a));

        // 5) projection 순서(최근순) 유지하면서 AccountItem 변환.
        List<AccountItem> items = recent.stream()
                .map(p -> {
                    BankAccount account = accountById.get(p.getBankAccountId());
                    RemittanceAmountProjection lastTx = lastByBankAccount.get(p.getBankAccountId());
                    return AccountItem.builder()
                            .bankCode(account != null ? account.getBank().getCode() : null)
                            .bankName(account != null ? account.getBank().getName() : null)
                            .accountNumber(account != null ? AccountNumberMasker.mask(account.getAccountNumber()) : null)
                            .accountHolder(lastTx != null ? lastTx.getReceiverName() : null)
                            .currencyCode(lastTx != null ? lastTx.getCurrencyCode().name() : null)
                            // 금액은 소수점 4자리 고정 string (잔액 조회 BalanceItem과 동일 규칙).
                            .lastAmount(lastTx != null ? lastTx.getAmount().setScale(4, RoundingMode.HALF_UP).toPlainString() : null)
                            .lastTransferredAt(RecentAccountsResponse.toUtcZ(p.getLastTransferredAt()))
                            .build();
                })
                .toList();

        return RecentAccountsResponse.of(items);
    }

    @Override
    public TransferFeeResponse getTransferFee(TransferFeeRequest request) {
        // 1) 통화 도메인 검증 — KRW/USD/PHP/VND 외는 TRANSFER4002. (형식 검증은 @Valid 단계에서 끝남)
        CurrencyType currency = CurrencyType.fromCode(request.currencyCode())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));

        // 2) 송금 유형 도메인 검증 — INTERNAL_TRANSFER/REMITTANCE 외는 TRANSFER4003.
        //    .filter로 CHARGE/EXCHANGE(다른 도메인 값)도 거부. currency 검증과 동일한 Optional 패턴.
        TransactionType transferType = TransactionType.fromCode(request.transferType())
                .filter(ALLOWED_TRANSFER_TYPES::contains)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE));

        // 3) amount 파싱. @Pattern으로 형식·양수 보장(0 차단 lookahead). 정규식 우회/프로그램 경로(Bean
        //    Validation 미적용) 방어를 위해 signum 검증을 한 번 더 둔다(이중 안전망). 0/음수는 COMMON4001.
        BigDecimal amount = new BigDecimal(request.amount());
        if (amount.signum() <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }

        // 4) 수수료 = 정책 헬퍼 (송금 실행과 정책 단일 진실로 공유).
        BigDecimal fee = calculateFee(transferType, amount);

        // 5) total = amount + fee. add는 scale = max(scale)을 따르므로 명시 setScale로 4자리 고정.
        BigDecimal totalDeduct = amount.add(fee).setScale(FEE_SCALE, RoundingMode.HALF_UP);

        return TransferFeeResponse.of(fee, currency, totalDeduct);
    }

    /**
     * 송금 정책 SSOT — 수수료 계산. 송금 수수료 조회({@link #getTransferFee})와 송금 실행
     * ({@link #executeInTransaction})이 같은 정책을 쓰도록 단일 헬퍼로 통일한다.
     * docs/remittance/api-spec.md §4 정책: INTERNAL_TRANSFER=0, REMITTANCE=amount×0.5%(HALF_UP 4자리).
     */
    /** WTX-05 — 지갑이 ACTIVE가 아니면(동결 SUSPENDED·폐쇄 CLOSED) WALLET4003(422)으로 차단한다. */
    private void requireActiveWallet(Wallet wallet) {
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BusinessException(WalletErrorCode.WALLET_INACTIVE);
        }
    }

    private BigDecimal calculateFee(TransactionType type, BigDecimal amount) {
        return switch (type) {
            case INTERNAL_TRANSFER -> BigDecimal.ZERO.setScale(FEE_SCALE, RoundingMode.HALF_UP);
            case REMITTANCE -> amount.multiply(REMITTANCE_FEE_RATE)
                    .setScale(FEE_SCALE, RoundingMode.HALF_UP);
            // ALLOWED_TRANSFER_TYPES 필터로 두 값만 통과되지만 enum 전체 case를 망라하는 안전망.
            default -> throw new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE);
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 송금 실행 (1단계: INTERNAL_TRANSFER + 같은 통화 전용)
    // 흐름: Layer1(Redis) → Layer2(DB) → 검증 → 락 → executeInTransaction → 캐시 → 락 해제
    //        race(UNIQUE 위반) → readPriorTransaction (Layer 3)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TransferExecuteResponse execute(String userPublicId, String idempotencyKey,
                                           TransferExecuteRequest request) {
        // 사용자 직접 호출(POST /transfers) — 송금 PIN 게이트 ON(TX-PIN).
        return executeInternal(userPublicId, idempotencyKey, request, true);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TransferExecuteResponse executePreAuthorized(String userPublicId, String idempotencyKey,
                                                        TransferExecuteRequest request) {
        // 사전 인가된 정기송금 회차 실행(스케줄러) — PIN은 설정 시 1회 검증한 standing order라 게이트 OFF(TX-PIN).
        // NOT_SUPPORTED 유지 필수: 스케줄러 executeSingle(REQUIRES_NEW)의 트랜잭션을 suspend해 자금 이동을
        // 독립 경계에서 커밋해야 한다(execute와 동일 사상).
        return executeInternal(userPublicId, idempotencyKey, request, false);
    }

    /**
     * 송금 실행 공통 본문. {@code requirePinGate}만 사용자 직접 호출({@link #execute}, true)과 사전 인가
     * 스케줄러({@link #executePreAuthorized}, false)를 가른다 — 그 외 멱등성 3-layer·rate-limit·락 재시도·
     * 자금 이동은 완전히 동일하다.
     */
    private TransferExecuteResponse executeInternal(String userPublicId, String idempotencyKey,
                                                    TransferExecuteRequest request, boolean requirePinGate) {
        // 비-HTTP 경로(스케줄러·내부 직접 호출) 서비스단 가드(WTX-02): HTTP는 컨트롤러
        // @RequestHeader("Idempotency-Key") @NotBlank로 막지만(WTX-01), 빈 키가 멱등 3-layer 키 스코프
        // (cacheKey/findByIdempotencyKey)를 무력화하므로 모든 side effect(rate-limit/캐시/DB) 전에
        // fail-fast로 COMMON4001 처리한다.
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }

        // 1) 입력 검증을 캐시 조회 전에 먼저 — cacheKey 생성에 transferType·scopeId가 필요하므로.
        //    enum/도메인 검증은 여기서, 형식은 @Valid에서.
        CurrencyType currency = CurrencyType.fromCode(request.currencyCode())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));
        TransactionType transferType = TransactionType.fromCode(request.transferType())
                .filter(ALLOWED_TRANSFER_TYPES::contains)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE));

        // 2) 같은 통화만 허용 (2단계까지 same-currency 강제 — 다통화는 3단계).
        if (!request.currencyCode().equals(request.receiveCurrencyCode())) {
            throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY_PAIR);
        }

        // 3) 도메인별 스코프 ID 확보 — REMITTANCE는 bank_account.public_id, INTERNAL은 receiver_public_id.
        //    이 값이 캐시 키 스코프와 race 응답 검증의 양쪽 기준이 된다(같은 idempotency_key를 다른
        //    (user, scope) 조합으로 재사용하면 cross-user 응답이 노출되지 않게).
        String scopeId = resolveScopeId(transferType, request);
        String cacheKey = cacheKey(transferType, idempotencyKey, userPublicId, scopeId);

        // 4) 멱등성 Layer 1 — Redis 캐시 (스코프된 키 hit만 즉시 반환 → cross-user 노출 차단).
        Optional<TransferExecuteResponse> cached = readFromCache(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 5) 멱등성 Layer 2 — DB 조회. 같은 idempotency_key 거래가 이미 있으면 rebuildFromPrior로
        //    소유자/유형/스코프 일치 검증 후 응답한다(검증 실패 시 ACCOUNT4001/WALLET4001로 모호 매핑).
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            TransferExecuteResponse resp = rebuildFromPrior(
                    existing.get(), userPublicId, transferType, scopeId);
            writeToCache(cacheKey, resp); // Layer 1 채워두기
            return resp;
        }

        // 5.5) Rate-limit — user 단위 고정 윈도. 폭주 차단(외부 자금 이동 보호). Redis 장애 시 fail-open(통과).
        //      초과 시 TRANSFER4006(429). 멱등 재요청(Layer1/2 hit)은 *신규 처리가 아니므로* 토큰을 소모하면
        //      안 된다(WTX-04) — 그래서 dedup 뒤에 둔다. 정당한 재시도가 토큰 고갈로 TRANSFER4006되는 것을 막고,
        //      rate-limit은 실제 신규 자금 이동(아래 6)에만 적용된다.
        boolean allowed = rateLimitHelper.tryAcquire(
                TRANSFER_RATE_LIMIT_PREFIX + userPublicId,
                transferRateLimitProperties.limit(),
                Duration.ofSeconds(transferRateLimitProperties.windowSeconds()));
        if (!allowed) {
            throw new BusinessException(TransferErrorCode.RATE_LIMIT_EXCEEDED);
        }

        // 5.7) 송금 PIN 서버측 게이트(TX-PIN) — 사용자 직접 호출만(requirePinGate). pin-verify 성공 마커를 원자
        //      소비(GETDEL)해, 마커가 없으면 PIN 미설정 TRANSFER4009 / 미검증 TRANSFER4010(428)으로 차단한다.
        //      위치: rate-limit 뒤·executeWithRetry 앞. ① 멱등 재요청(Layer1/2 hit)은 위에서 이미 반환돼 여기
        //      도달하지 않으므로 재검증을 요구하지 않는다. ② rate-limit으로 막힌 요청은 마커를 소비하지 않아
        //      (게이트 미도달) 검증을 헛되이 태우지 않는다. ③ 마커는 executeWithRetry의 락경합 재시도 *바깥*에서
        //      1회만 소비된다(재시도가 마커를 다시 요구하지 않음). rate-limit(5.5)과 달리 Redis 장애 시 fail-closed.
        //      정기송금 회차(executePreAuthorized)는 설정 시 1회 인가한 standing order라 이 게이트를 면제한다.
        if (requirePinGate) {
            transferPinGate.requireVerified(userPublicId);
        }

        // 6) 실 처리 + 락 경합 재시도 래퍼 — race(UNIQUE 위반)와 락 경합을 도메인별 분기에 공통 처리.
        TransferExecuteResponse response = executeWithRetry(
                userPublicId, idempotencyKey, request, currency, transferType, scopeId);

        // 7) 커밋 이후 Redis 캐시 채우기 (Layer 1).
        writeToCache(cacheKey, response);
        return response;
    }

    /**
     * 송금 실제 처리 + 동시성 예외 복구 (충전 {@code doChargeWithRetry} 패턴 답습).
     *
     * <ul>
     *   <li><b>{@link DataIntegrityViolationException}</b>(idempotency_key UNIQUE 위반): 다른 트랜잭션이
     *       같은 키로 먼저 커밋. 별도 readOnly 트랜잭션({@link #readPriorTransaction})으로 첫 결과 재반환.</li>
     *   <li><b>{@link PessimisticLockingFailureException}</b>(락 경합·데드락): InnoDB가 패자 트랜잭션을
     *       롤백. 일시적 충돌이라 최대 {@link #MAX_TRANSFER_ATTEMPTS}회 재시도. 소진 시 COMMON5031(503).</li>
     * </ul>
     */
    private TransferExecuteResponse executeWithRetry(
            String userPublicId, String idempotencyKey, TransferExecuteRequest request,
            CurrencyType currency, TransactionType transferType, String scopeId) {
        int attempt = 0;
        while (true) {
            try {
                if (transferType == TransactionType.REMITTANCE) {
                    return executeRemittancePath(userPublicId, idempotencyKey, request, currency);
                }
                return executeInternalTransferPath(userPublicId, idempotencyKey, request, currency, transferType);
            } catch (DataIntegrityViolationException race) {
                return self.readPriorTransaction(idempotencyKey, userPublicId, transferType, scopeId);
            } catch (PessimisticLockingFailureException lockContention) {
                if (++attempt >= MAX_TRANSFER_ATTEMPTS) {
                    log.warn("송금 락 경합 재시도 소진({}회) — COMMON5031 매핑. user={}",
                            MAX_TRANSFER_ATTEMPTS, userPublicId, lockContention);
                    throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, lockContention);
                }
                log.debug("송금 락 경합 — 재시도 {}/{}. user={}", attempt, MAX_TRANSFER_ATTEMPTS, userPublicId);
            }
        }
    }

    /**
     * INTERNAL_TRANSFER pre-tx 경로 — wallet 조회·self-check·분산 락 획득 후 트랜잭션 내부로 위임한다.
     * 원래 {@code execute()} 안에 인라인이었으나, {@link #executeWithRetry}의 분기에서 호출하도록 분리.
     */
    private TransferExecuteResponse executeInternalTransferPath(
            String userPublicId, String idempotencyKey, TransferExecuteRequest request,
            CurrencyType currency, TransactionType transferType) {
        // 송신/수신 wallet 조회 (락 키용 id 확보).
        Wallet senderWallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
        Wallet receiverWallet = walletRepository.findByUserPublicId(request.receiverPublicId())
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // WTX-05 — 동결(SUSPENDED)/폐쇄(CLOSED) 지갑은 송신·수신 모두 차단(ACTIVE만 허용). 송신자뿐 아니라
        // 수신자도 비활성이면 입금하지 않는다(비활성 지갑에 돈이 쌓이는 것 방지).
        requireActiveWallet(senderWallet);
        requireActiveWallet(receiverWallet);

        // 자기 자신에게 송금 차단 (이후 MultiLock에서 같은 키 2회 잠금 문제 회피도 겸함).
        if (senderWallet.getId().equals(receiverWallet.getId())) {
            throw new BusinessException(TransferErrorCode.SELF_TRANSFER_NOT_ALLOWED);
        }

        // 분산 락 획득 (wallet_id 오름차순 — DistributedLockHelper 내부 정책). 실패 → 503.
        RLock lock = distributedLockHelper.tryLockTwoWallets(
                senderWallet.getId(), receiverWallet.getId());
        if (lock == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            // 트랜잭션 내 실제 처리 (self-proxy로 호출 — 직접 호출 시 @Transactional 미적용).
            return self.executeInTransaction(
                    senderWallet.getId(), receiverWallet.getId(),
                    currency, transferType, idempotencyKey, request);
        } finally {
            // 락 보유자가 본인인 경우에만 해제 (lease 만료로 다른 스레드가 가진 경우 안전).
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ----- 멱등성 캐시 키 + race 응답 검증 헬퍼 -----

    /**
     * 도메인별 스코프 ID 추출. 캐시 키와 race 검증의 양쪽 기준이자, 도메인별 <b>조건부 필수 필드</b> 검증
     * 지점이다(조건부 필수는 Bean Validation으로 표현이 까다로워 Service에서 — validateScheduled와 동일).
     * <ul>
     *   <li>REMITTANCE → bank_account.public_id (없으면 COMMON4001)</li>
     *   <li>INTERNAL_TRANSFER → receiver_public_id (없으면 COMMON4001 — TX1: DTO @NotBlank 제거로 이리 이동.
     *       무조건 @NotBlank면 REMITTANCE가 @Valid에서 거부돼 HTTP 도달 불가였다)</li>
     * </ul>
     */
    private static String resolveScopeId(TransactionType transferType, TransferExecuteRequest request) {
        return switch (transferType) {
            case REMITTANCE -> {
                String bankAccountPublicId = request.bankAccountPublicId();
                if (bankAccountPublicId == null || bankAccountPublicId.isBlank()) {
                    throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
                }
                yield bankAccountPublicId;
            }
            case INTERNAL_TRANSFER -> {
                String receiverPublicId = request.receiverPublicId();
                if (receiverPublicId == null || receiverPublicId.isBlank()) {
                    throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
                }
                yield receiverPublicId;
            }
            default -> throw new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE);
        };
    }

    /**
     * 멱등성 캐시 키 — (도메인, idempotency_key, 요청자, 스코프) 4-튜플로 스코프한다.
     * 같은 idempotency_key라도 (user, scope)가 다르면 캐시 hit이 나지 않아 DB 경로의 rebuildFromPrior로
     * 흘러 거기서 ACCOUNT4001/WALLET4001로 차단된다 — Layer 1·2·3 모두 cross-user 노출 차단(충전 동일 정책).
     * {@code IdempotencyCacheHelper}가 {@code idempotency:} prefix를 덧붙이므로 최종 키는
     * {@code idempotency:{remittance|internal_transfer}:{key}:{user}:{scope}}다.
     */
    private static String cacheKey(TransactionType transferType, String idempotencyKey,
                                   String userPublicId, String scopeId) {
        String domain = switch (transferType) {
            case REMITTANCE -> "remittance";
            case INTERNAL_TRANSFER -> "internal_transfer";
            default -> throw new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE);
        };
        return domain + ":" + idempotencyKey + ":" + userPublicId + ":" + scopeId;
    }

    /**
     * 이미 처리된 거래로부터 본 요청의 응답으로 재현해도 되는지 검증한 뒤 응답한다(멱등성 재반환).
     *
     * <p>{@code idempotency_key}는 전역 UNIQUE라 다른 사용자/유형/스코프의 거래가 잡힐 수 있다.
     * 다음을 확인하고, 아니면 도메인 부재 에러로 차단한다(존재 여부 미노출 — 충전 동일 정책):
     * <ul>
     *   <li>키 소유자(거래 지갑 주인) == 요청자</li>
     *   <li>거래 유형 == 요청 유형</li>
     *   <li>거래의 스코프 == 요청 스코프
     *       (REMITTANCE면 bank_account.public_id, INTERNAL은 receiver wallet의 user_public_id)</li>
     * </ul>
     */
    private TransferExecuteResponse rebuildFromPrior(
            Transaction prior, String userPublicId,
            TransactionType expectedType, String expectedScopeId) {
        if (!prior.getWallet().getUserPublicId().equals(userPublicId)
                || prior.getType() != expectedType) {
            throw new BusinessException(scopeMismatchError(expectedType));
        }
        if (expectedType == TransactionType.REMITTANCE) {
            // REMITTANCE: bank_account_id로 풀어 public_id 비교. 거래엔 internal id만 있음.
            BankAccount priorAccount = bankAccountRepository.findById(prior.getBankAccountId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
            if (!priorAccount.getPublicId().equals(expectedScopeId)) {
                throw new BusinessException(scopeMismatchError(expectedType));
            }
        } else { // INTERNAL_TRANSFER
            Wallet priorReceiver = prior.getReceiverWallet();
            if (priorReceiver == null
                    || !priorReceiver.getUserPublicId().equals(expectedScopeId)) {
                throw new BusinessException(scopeMismatchError(expectedType));
            }
        }
        return TransferExecuteResponse.from(prior);
    }

    /**
     * race 검증 실패 시 매핑 에러. 충전 정책 답습 — 사유 미구분으로 정보 누설 방지.
     */
    private static com.gb.common.exception.ErrorCode scopeMismatchError(TransactionType type) {
        return type == TransactionType.REMITTANCE
                ? AccountErrorCode.ACCOUNT_NOT_FOUND
                : WalletErrorCode.WALLET_NOT_FOUND;
    }

    /**
     * 회원 본명을 안전하게 조회한다(INTERNAL_TRANSFER 송금 시 receiver_name snapshot, 그리고 송금 확인증의
     * sender_name 조회용으로 공용 사용).
     *
     * <p>송금 확인증은 격식 있는 영수증 문서라 본명({@link MemberInfo#name})을 사용한다(닉네임이 아님).
     *
     * <p><b>fail-open:</b> MemberClient 장애·timeout이 본업(송금/확인증 응답)을 막지 않도록, 어떤 예외라도
     * 잡아 {@code null}을 반환한다. 외부 의존 장애 시 receiverName이 null로 저장되며, 송금 자체는 정상
     * 진행한다. MockMemberClient는 fallback {@code "Unknown"}까지 반환하므로 일반적으로 null이 나오지
     * 않지만, 운영 RealMemberClient 도입 후 HTTP 장애·5xx 응답을 흡수하는 안전망이다.
     */
    private String fetchMemberNameSafe(String userPublicId) {
        try {
            MemberInfo info = memberClient.getMember(userPublicId);
            return info != null ? info.name() : null;
        } catch (RuntimeException e) {
            log.warn("MemberClient 조회 실패 — name=null 처리. user={}", userPublicId, e);
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 송금 확인증 조회 (GET /api/v1/transfers/{publicId}/receipt)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TransferReceiptResponse getReceipt(String userPublicId, String transferPublicId) {
        // (1) 거래 조회 — 없으면 TRANSFER4001 (정보 누설 방지로 미존재·권한·유형 실패 모두 동일 코드).
        Transaction tx = transactionRepository.findByPublicId(transferPublicId)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.TRANSFER_NOT_FOUND));

        // (2) 본인 검증 — 송신자(거래 wallet 주인)만 조회 가능. 수신자는 별도 "받은 거래 내역" API 영역.
        //     실패 시 동일 TRANSFER4001로 모호 매핑(rebuildFromPrior 정책 답습 — cross-user 응답 노출 차단).
        if (!tx.getWallet().getUserPublicId().equals(userPublicId)) {
            throw new BusinessException(TransferErrorCode.TRANSFER_NOT_FOUND);
        }

        // (3) 송금 유형 검증 — INTERNAL_TRANSFER/REMITTANCE만. 충전·환전·기타는 확인증 대상 아님.
        TransactionType type = tx.getType();
        if (type != TransactionType.INTERNAL_TRANSFER && type != TransactionType.REMITTANCE) {
            throw new BusinessException(TransferErrorCode.TRANSFER_NOT_FOUND);
        }

        // (4) 송신자 본명 조회(MemberClient fail-open). 본인이라 호출 실패 시 null이어도 영수증 자체는 응답.
        String senderName = fetchMemberNameSafe(userPublicId);

        // (5) 도메인별 부가 데이터 조달.
        //     INTERNAL은 외부 계좌 없음 → bankAccount=null로 응답(bankName·accountNumber 모두 null).
        //     REMITTANCE는 bank_account_id로 BankAccount를 풀어 bank명·계좌번호(마스킹)를 응답에 채운다.
        //     bank_account_id가 어떤 이유로든 사라진 비정상 상태는 정합성 위반 → COMMON5000.
        BankAccount bankAccount = null;
        if (type == TransactionType.REMITTANCE) {
            bankAccount = bankAccountRepository.findById(tx.getBankAccountId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        }

        return TransferReceiptResponse.of(tx, senderName, bankAccount);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 정기 송금 대상 유효성 검증 (POST /api/v1/transfers/scheduled/validate)
    // 사전 검증 — 도메인 검증 미통과는 200 + is_valid=false + reason.
    // 입력·계좌·통화 enum 오류 등은 도메인 에러(400/403/404)로 BusinessException 전파.
    // ──────────────────────────────────────────────────────────────────────────

    /** 1·2단계 same-currency 강제로 인한 미통과 사유. 3단계(다통화) 도입 시 본 메시지 갱신. */
    private static final String REASON_SAME_CURRENCY_REQUIRED =
            "1·2단계는 같은 통화 송금만 지원합니다. 다통화는 3단계 도입 후 지원 예정.";

    @Override
    @Transactional(readOnly = true)
    public ValidateScheduledResponse validateScheduled(String userPublicId, ValidateScheduledRequest request) {
        // (1) transfer_type 파싱 — 허용 유형(INTERNAL_TRANSFER/REMITTANCE) 외는 TRANSFER4003.
        TransactionType type = TransactionType.fromCode(request.transferType())
                .filter(ALLOWED_TRANSFER_TYPES::contains)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE));

        // (2) 통화 enum 검증 — 미지원 통화는 TRANSFER4002.
        CurrencyType currency = CurrencyType.fromCode(request.currencyCode())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));
        CurrencyType receiveCurrency = CurrencyType.fromCode(request.receiveCurrencyCode())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));

        // (3) 도메인별 대상 검증. 조건부 필수 필드는 Bean Validation으로 표현이 까다로워 여기서 검증.
        if (type == TransactionType.REMITTANCE) {
            // bank_account_public_id 필수 → 누락 시 COMMON4001.
            String accountPublicId = request.bankAccountPublicId();
            if (accountPublicId == null || accountPublicId.isBlank()) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            }
            // 본인 소유 + 활성 계좌만 통과 (사유 미구분으로 정보 누설 방지 — REMITTANCE 송금 정책 동일).
            BankAccount account = bankAccountRepository
                    .findByPublicIdAndUserPublicIdAndIsActiveTrue(accountPublicId, userPublicId)
                    .orElseThrow(() -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND));
            // 계좌 인증 토큰 검증 — 송금 실행 시 차단되는 케이스를 사전 단계에서 미리 알린다.
            if (account.getMockAccountToken() == null) {
                throw new BusinessException(AccountErrorCode.UNVERIFIED_ACCOUNT);
            }
        } else {
            // INTERNAL_TRANSFER → receiver_public_id 필수.
            String receiverPublicId = request.receiverPublicId();
            if (receiverPublicId == null || receiverPublicId.isBlank()) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            }
            // 자기 자신 차단(송금 실행 정책 동일).
            if (receiverPublicId.equals(userPublicId)) {
                throw new BusinessException(TransferErrorCode.SELF_TRANSFER_NOT_ALLOWED);
            }
            // 수신자 wallet 존재 확인 — 없으면 송금 자체 불가능하므로 사전 단계에서 차단.
            walletRepository.findByUserPublicId(receiverPublicId)
                    .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
        }

        // (4) same-currency 검증 — 1·2단계 강제. 다르면 미통과(200 + reason). 3단계 도입 시 분기 완화.
        if (currency != receiveCurrency) {
            return ValidateScheduledResponse.invalid(REASON_SAME_CURRENCY_REQUIRED);
        }

        return ValidateScheduledResponse.valid();
    }

    @Override
    @Transactional
    public TransferExecuteResponse executeInTransaction(
            Long senderWalletId, Long receiverWalletId,
            CurrencyType currency, TransactionType transferType,
            String idempotencyKey, TransferExecuteRequest request) {

        // 멱등성 Layer 3(idempotency_key UNIQUE 위반) catch는 상위 executeWithRetry로 위임 — 거기서
        // rebuildFromPrior 검증을 거친 readPriorTransaction(REQUIRES_NEW)으로 첫 결과를 재반환한다.
        // 본 메서드는 UNIQUE 위반 시 자연스럽게 DataIntegrityViolationException을 throw해 메인 tx가
        // rollback되도록 한다 (spring @Transactional 자동 rollback).

        // REMITTANCE는 별도 in-tx 경로(외부 계좌 + 단일 송신자 잔액). INTERNAL_TRANSFER 코드는 그대로 유지.
        if (transferType == TransactionType.REMITTANCE) {
            return executeRemittanceInTransaction(senderWalletId, currency, idempotencyKey, request);
        }
        // INTERNAL_TRANSFER 본체. DataIntegrityViolationException은 상위 executeWithRetry가 잡는다.
        {

            // (1) wallet 재조회 — execute()의 엔티티는 이 트랜잭션 컨텍스트 밖에서 로드돼 detached.
            //     수신자 지갑은 상류 execute()의 findByUserPublicId로 이미 검증된 상태(없으면 거기서 WALLET4001).
            Wallet senderWallet = walletRepository.findById(senderWalletId)
                    .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
            Wallet receiverWallet = walletRepository.findById(receiverWalletId)
                    .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

            // (2) 수신자 잔액 행 0원 보장 — REQUIRES_NEW로 독립 커밋(이미 있으면 no-op, 동시 생성 race 흡수).
            //     이래야 (3)의 FOR UPDATE가 존재하지 않는 행을 잠그려다 실패하지 않는다.
            //     송신자는 자동 생성하지 않는다 — 돈을 보내려면 잔액 행이 이미 있어야 정상.
            walletBalanceWriter.ensureBalanceRow(receiverWallet, currency);

            // (3) 비관적 락 — wallet_id 오름차순으로 잡아 데드락 회피(분산 락 정책과 동일 방향).
            //     락 SQL 발행 순서는 lower → higher 그대로 유지하고, 결과는 역할(sender/receiver)로 재매핑.
            long lowerId = Math.min(senderWalletId, receiverWalletId);
            boolean senderIsLower = senderWalletId.equals(lowerId);
            Wallet lowerWallet = senderIsLower ? senderWallet : receiverWallet;
            Wallet higherWallet = senderIsLower ? receiverWallet : senderWallet;

            Optional<WalletBalance> lowerBalanceOpt = walletBalanceRepository
                    .findForUpdateByWalletAndCurrency(lowerWallet, currency);
            Optional<WalletBalance> higherBalanceOpt = walletBalanceRepository
                    .findForUpdateByWalletAndCurrency(higherWallet, currency);

            // (4) 역할별 부재 사유 분기 — id 순이 아니라 sender/receiver 역할로 에러 코드를 가른다.
            //     송신자 행 없음 = 도메인 에러(돈이 있어야 보냄, WALLET4001).
            //     수신자 행 없음 = ensure 직후라 정합성 깨진 비정상 상태 → COMMON5000 (ChargeServiceImpl 동일 정책).
            Optional<WalletBalance> senderBalanceOpt = senderIsLower ? lowerBalanceOpt : higherBalanceOpt;
            Optional<WalletBalance> receiverBalanceOpt = senderIsLower ? higherBalanceOpt : lowerBalanceOpt;
            WalletBalance senderBalance = senderBalanceOpt
                    .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
            WalletBalance receiverBalance = receiverBalanceOpt
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));

            // (3) 금액 + 수수료 계산. INTERNAL_TRANSFER는 fee=0이라 totalDeduct == amount.
            BigDecimal amount = new BigDecimal(request.amount());
            BigDecimal fee = calculateFee(transferType, amount);
            BigDecimal totalDeduct = amount.add(fee).setScale(FEE_SCALE, RoundingMode.HALF_UP);

            // (4) 잔액 검증 — 부족하면 WALLET4002. (WalletBalance.subtract도 내부 방어가 있지만,
            //     사용자 향 비즈니스 코드를 명시하려면 Service에서 사전 검증해야 한다.)
            if (senderBalance.getBalance().compareTo(totalDeduct) < 0) {
                throw new BusinessException(WalletErrorCode.INSUFFICIENT_BALANCE);
            }

            // (5) 잔액 변경 — dirty checking으로 UPDATE.
            BigDecimal senderBefore = senderBalance.getBalance();
            BigDecimal receiverBefore = receiverBalance.getBalance();
            senderBalance.subtract(totalDeduct);
            receiverBalance.addBalance(amount); // 수신자는 fee 없이 amount만
            BigDecimal senderAfter = senderBalance.getBalance();
            BigDecimal receiverAfter = receiverBalance.getBalance();

            // (6-a) 수신자 본명 snapshot — 송금 확인증의 receiver_name 출처.
            //     fail-open: MemberClient 장애로 본업(송금)을 막지 않는다. 실패 시 receiverName=null로 저장.
            String receiverName = fetchMemberNameSafe(receiverWallet.getUserPublicId());

            // (6) Transaction INSERT — idempotency_key UNIQUE 위반 시 catch로 Layer 3 흐름.
            Transaction transaction = transactionRepository.save(Transaction.builder()
                    .publicId(UUID.randomUUID().toString())
                    .wallet(senderWallet)
                    .type(transferType)
                    .amount(amount)
                    .currencyCode(currency)
                    .fee(fee)
                    .status(TransactionStatus.COMPLETED)
                    .idempotencyKey(idempotencyKey)
                    .receiverWallet(receiverWallet)
                    .receiverName(receiverName)         // snapshot — 확인증 receiver_name
                    .receiveAmount(amount)              // 1단계 같은 통화 — receive == amount
                    .receiveCurrencyCode(currency)      // 1단계 — receive currency == send currency
                    .exchangeRate(null)                 // 1단계 — 환율 미적용
                    .memo(request.memo())
                    .build());

            // (7) 감사 로그 INSERT × 2 (append-only). 1단계 action은 SEND/RECEIVE 구분.
            auditLogRepository.save(TransactionAuditLog.builder()
                    .transaction(transaction)
                    .userPublicId(senderWallet.getUserPublicId())
                    .action("INTERNAL_TRANSFER_SEND")
                    .amount(totalDeduct)                  // 차감액(amount + fee)
                    .currencyCode(currency)
                    .beforeBalance(senderBefore)
                    .afterBalance(senderAfter)
                    .status(TransactionStatus.COMPLETED)
                    .build());
            auditLogRepository.save(TransactionAuditLog.builder()
                    .transaction(transaction)
                    .userPublicId(receiverWallet.getUserPublicId())
                    .action("INTERNAL_TRANSFER_RECEIVE")
                    .amount(amount)                       // 수령액
                    .currencyCode(currency)
                    .beforeBalance(receiverBefore)
                    .afterBalance(receiverAfter)
                    .status(TransactionStatus.COMPLETED)
                    .build());

            return TransferExecuteResponse.from(transaction);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TransferExecuteResponse readPriorTransaction(
            String idempotencyKey, String userPublicId,
            TransactionType expectedType, String expectedScopeId) {
        Transaction prior = transactionRepository.findByIdempotencyKey(idempotencyKey)
                // race로 들어왔는데 키가 사라졌다면 일관성이 깨진 비정상 상태 → 서버 오류.
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        // 키 소유자/유형/스코프 검증 — cross-user 응답 노출 차단.
        return rebuildFromPrior(prior, userPublicId, expectedType, expectedScopeId);
    }

    // ----- Redis 캐시 헬퍼: 캐시 실패는 응답을 막지 않는다(성능 최적화 계층) -----

    private Optional<TransferExecuteResponse> readFromCache(String idempotencyKey) {
        try {
            return idempotencyCacheHelper.get(idempotencyKey).flatMap(json -> {
                try {
                    return Optional.of(objectMapper.readValue(json, TransferExecuteResponse.class));
                } catch (JsonProcessingException e) {
                    log.warn("Idempotency cache JSON 역직렬화 실패 — Layer 2로 폴백. key={}", idempotencyKey, e);
                    return Optional.empty();
                }
            });
        } catch (RuntimeException e) {
            log.warn("Redis 캐시 조회 실패 — Layer 2로 폴백. key={}", idempotencyKey, e);
            return Optional.empty();
        }
    }

    private void writeToCache(String idempotencyKey, TransferExecuteResponse response) {
        try {
            idempotencyCacheHelper.set(idempotencyKey, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException | RuntimeException e) {
            // 캐시 쓰기 실패는 응답에 영향 없음 — 다음 동일 키 요청 시 Layer 2가 받아준다.
            log.warn("Idempotency cache 저장 실패. key={}", idempotencyKey, e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // REMITTANCE (2단계: 외부 계좌 현금화, same-currency 강제)
    // INTERNAL_TRANSFER와 달리 수신자가 외부 은행 계좌 → receiver wallet/self-check/분산 락 모두 미적용.
    // 직렬화는 송신자 단일 잔액 행 비관적 락(findForUpdateByWalletAndCurrency)만으로 충분.
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * REMITTANCE pre-tx 경로 — bank_account_public_id 존재 검증과 송신자 wallet 조회만 하고 트랜잭션 내부로
     * 위임한다. {@code execute()}의 INTERNAL_TRANSFER 경로를 건드리지 않으려 별도 헬퍼로 분리.
     *
     * <p>INTERNAL_TRANSFER와의 차이:
     * <ul>
     *   <li>receiver wallet 조회 없음 (외부 계좌).</li>
     *   <li>self-transfer 체크 없음 (송신자 wallet vs 외부 계좌라 자기 판정 불가).</li>
     *   <li>{@link DistributedLockHelper} 사용 안 함 — 단일 송신자 wallet만 잠그면 충분하므로
     *       in-tx 단계에서 DB 비관적 락(FOR UPDATE)으로 직렬화한다.</li>
     * </ul>
     */
    private TransferExecuteResponse executeRemittancePath(
            String userPublicId, String idempotencyKey, TransferExecuteRequest request, CurrencyType currency) {
        // bank_account_public_id 존재 검증은 execute() 진입 시 resolveScopeId에서 이미 처리됨.

        // 송신자 wallet 조회 (락 키용 id 확보). receiver/lock 없음.
        Wallet senderWallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // self-proxy로 호출 — 직접 호출 시 @Transactional 미적용(INTERNAL_TRANSFER 경로와 동일).
        // receiverWalletId는 null (외부 계좌). executeInTransaction이 transferType으로 분기.
        // 캐시 저장은 execute()가 일괄 처리(race·재시도 시 중복 호출 방지).
        return self.executeInTransaction(
                senderWallet.getId(), null, currency, TransactionType.REMITTANCE, idempotencyKey, request);
    }

    /**
     * REMITTANCE in-tx 경로 — 메인 {@code @Transactional} 안에서 BankClient.payout을 호출한다(결정 C).
     *
     * <p><b>외부 호출 직전 시도 흔적 별도 커밋:</b> 잔액 검증 직후, payout 호출 *직전*에
     * {@link RemittanceAttemptWriter#record}로 {@code remittance_attempts}에 1행을 REQUIRES_NEW로
     * 커밋한다. 메인 트랜잭션이 payout 예외/이후 단계로 rollback돼도 흔적은 살아남아 timeout-but-success
     * 시 운영 reconcile 입력 자료가 된다. 같은 키 재시도는 Writer 내부 UNIQUE 위반 흡수로 1행만 유지.
     *
     * <p>흔적을 {@code transaction_audit_logs}가 아닌 신규 {@code remittance_attempts}에 박는 이유:
     * audit log는 {@code transaction_id} NOT NULL이라 본 Transaction INSERT 전엔 행을 만들 수 없고, 또
     * "거래 1:1 흔적" 의미를 흐린다. 별도 테이블로 분리해 충전·INTERNAL_TRANSFER 흐름엔 영향 없게 한다
     * — database.md {@code remittance_attempts} 섹션 SSOT.
     *
     * <p>reconcile 배치는 본 PR에 미포함(향후 운영 도입 시 본 테이블을 입력으로 사용).
     */
    private TransferExecuteResponse executeRemittanceInTransaction(
            Long senderWalletId, CurrencyType currency, String idempotencyKey, TransferExecuteRequest request) {
        // (1) 송신자 wallet 재조회 — execute()의 엔티티는 이 트랜잭션 컨텍스트 밖에서 로드돼 detached.
        Wallet senderWallet = walletRepository.findById(senderWalletId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // (1.5) WTX-05 — 동결/폐쇄 지갑은 송금 차단(ACTIVE만 허용). REMITTANCE는 수취가 외부 계좌라 송신자만 본다.
        requireActiveWallet(senderWallet);

        // (2) 본인 소유 + 활성 계좌 조회 (충전과 동일 패턴). 사유 미구분 — 정보 누설 방지로 ACCOUNT4001 통일.
        BankAccount account = bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(
                        request.bankAccountPublicId(), senderWallet.getUserPublicId())
                .orElseThrow(() -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND));

        // (3) 계좌 인증 토큰 확인 — 없으면 Mock 호출 전에 ACCOUNT4006 차단(충전과 동일 정책).
        if (account.getMockAccountToken() == null) {
            throw new BusinessException(AccountErrorCode.UNVERIFIED_ACCOUNT);
        }

        // (4) 송신자 잔액 행 FOR UPDATE — 단일 행이라 데드락 회피용 ordering 불필요.
        //     송신자는 자동 생성 안 함(돈을 보내려면 잔액 행이 이미 있어야 정상).
        //
        //     ⚠️ 알려진 한계(WTX-03): 이 FOR UPDATE 락은 아래 (7) 외부 Mock 은행 payout(HTTP)이 끝날 때까지
        //        유지된다 — 외부 호출 동안 송신자 잔액 행이 잠겨 있어, 같은 송신자의 다른 송금/환전이 락 대기한다.
        //        현재는 (a) 단일 행·단일 사용자라 락 경합 캐스케이드가 낮고, (b) BankClient에 타임아웃이 구현돼
        //        무한 보유는 없어 위험이 낮다. payout을 락 밖 짧은 별도 tx로 빼면(reserve→payout→confirm Saga)
        //        락 보유 시간은 줄지만, payout 성공 후 로컬 차감 실패 시 외부만 빠져나가는 정합성 창과 보상(환불)
        //        로직이 새로 필요해 정합성 모델이 바뀐다. 이는 팀 합의 + 진짜 MySQL(Testcontainers) 검증을 선행해야
        //        안전하므로 본 사이클 범위 밖으로 연기한다(외부 성공 흔적은 (6.5) remittance_attempts로 이미 보존).
        WalletBalance senderBalance = walletBalanceRepository
                .findForUpdateByWalletAndCurrency(senderWallet, currency)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // (5) 금액 + 수수료 계산 (REMITTANCE = amount × 0.5%, HALF_UP scale 4 — calculateFee 정책 SSOT).
        BigDecimal amount = new BigDecimal(request.amount());
        BigDecimal fee = calculateFee(TransactionType.REMITTANCE, amount);
        BigDecimal totalDeduct = amount.add(fee).setScale(FEE_SCALE, RoundingMode.HALF_UP);

        // (6) 잔액 검증 — 부족하면 WALLET4002. (WalletBalance.subtract 내부 방어도 있지만 비즈니스 코드 명시.)
        if (senderBalance.getBalance().compareTo(totalDeduct) < 0) {
            throw new BusinessException(WalletErrorCode.INSUFFICIENT_BALANCE);
        }

        // (6.5) 외부 호출 *직전* 시도 흔적을 REQUIRES_NEW로 별도 커밋 — payout 실패/timeout 시 메인 tx가
        //       rollback돼도 흔적은 남아 운영 reconcile 입력이 된다. 같은 idempotency_key 재시도/race는
        //       Writer 내부 UNIQUE 위반 흡수로 1행만 유지(상세: RemittanceAttemptWriter javadoc).
        remittanceAttemptWriter.record(idempotencyKey, senderWallet.getUserPublicId(),
                account.getId(), totalDeduct, currency);

        // (7) Mock 은행 지급. 외부 에러는 BankErrorMapper가 BusinessException으로 변환해 던지므로 그대로 전파
        //     (BANK4002→ACCOUNT4003, BANK4040→ACCOUNT4001, BANK4003→ACCOUNT4002, BANK4010→ACCOUNT4006,
        //      5xx/네트워크→COMMON5031). idempotencyKey forward — Mock도 같은 키로 첫 응답을 재반환한다.
        //     예외 전파 시 메인 @Transactional rollback으로 송신자 잔액 변경 없음(아직 차감 전이라 무영향).
        PayoutResult payoutResult = bankClient.payout(
                account.getBank().getCode(), account.getAccountNumber(),
                amount, currency.name(), idempotencyKey);
        // 방어 — 정상 응답이지만 status가 COMPLETED 아닐 때는 일시 장애로 본다(충전 패턴 동일).
        if (payoutResult == null || !"COMPLETED".equals(payoutResult.status())) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        // WTX-09 — 은행이 처리한 금액·통화가 요청과 일치하는지 대조한다(status만 믿지 않음). 불일치는
        //   부분처리/통화오류 등 정합성 깨짐이라 잔액 차감 없이 일시 장애(503)로 보류한다(reconcile 대상, 충전 대칭).
        if (payoutResult.amount() == null || payoutResult.amount().compareTo(amount) != 0
                || !currency.name().equals(payoutResult.currencyCode())) {
            log.error("송금 payout 응답 금액/통화 불일치 — 요청 {}{} vs 응답 {}{}. key={}",
                    amount, currency.name(), payoutResult.amount(), payoutResult.currencyCode(), idempotencyKey);
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }

        // (8) 잔액 차감 — dirty checking으로 UPDATE.
        BigDecimal senderBefore = senderBalance.getBalance();
        senderBalance.subtract(totalDeduct);
        BigDecimal senderAfter = senderBalance.getBalance();

        // (9) Transaction INSERT — idempotency_key UNIQUE 위반 시 상위 catch가 Layer 3 흐름으로 흡수.
        //     bank_account_id = 검증된 계좌의 내부 id (database.md REMITTANCE 행 — 수취 계좌 컬럼).
        //     receiver_wallet_id = null (외부 계좌). receiver_name = bankAccount.holderName snapshot —
        //       송금 확인증의 receiver_name 출처. 컬럼 추가 전 등록된 기존 계좌면 null(허용).
        //     receive_amount/receive_currency_code = amount/currency (same-currency 강제 — 다통화는 3단계).
        //     exchange_rate = null (same-currency).
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .publicId(UUID.randomUUID().toString())
                .wallet(senderWallet)
                .type(TransactionType.REMITTANCE)
                .amount(amount)
                .currencyCode(currency)
                .fee(fee)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(idempotencyKey)
                .bankAccountId(account.getId())
                .receiverName(account.getHolderName())   // snapshot — 확인증 receiver_name
                .receiveAmount(amount)
                .receiveCurrencyCode(currency)
                .exchangeRate(null)
                .memo(request.memo())
                .build());

        // (10) 감사 로그 INSERT (append-only) — REMITTANCE는 송신자 한 줄만(외부 계좌라 수신 audit 없음).
        //      action = "REMITTANCE" (String 리터럴, enum화 안 함 — 결정 (4)).
        auditLogRepository.save(TransactionAuditLog.builder()
                .transaction(transaction)
                .userPublicId(senderWallet.getUserPublicId())
                .action("REMITTANCE")
                .amount(totalDeduct)             // 차감액(amount + fee) — INTERNAL_TRANSFER_SEND 패턴 동일
                .currencyCode(currency)
                .beforeBalance(senderBefore)
                .afterBalance(senderAfter)
                .status(TransactionStatus.COMPLETED)
                .build());

        return TransferExecuteResponse.from(transaction);
    }
}
