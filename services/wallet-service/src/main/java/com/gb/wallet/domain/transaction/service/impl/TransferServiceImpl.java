package com.gb.wallet.domain.transaction.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.dto.request.TransferFeeRequest;
import com.gb.wallet.domain.transaction.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse.AccountItem;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse.RecipientItem;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse.CurrencyItem;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferFeeResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.ReceiverCurrencyProjection;
import com.gb.wallet.domain.transaction.repository.RecentAccountProjection;
import com.gb.wallet.domain.transaction.repository.RecentRecipientProjection;
import com.gb.wallet.domain.transaction.repository.RemittanceAmountProjection;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.RemittanceAttemptWriter;
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
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.common.util.AccountNumberMasker;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.MemberErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.DistributedLockHelper;
import com.gb.wallet.global.redis.IdempotencyCacheHelper;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferServiceImpl implements TransferService {

    /** 명세상 고정 10명. 페이지네이션 없음. */
    private static final int RECENT_LIMIT = 10;

    // 송금 수수료 정책 상수.
    // TODO: 수수료 정책 확정 시 정책 테이블/외부 조회로 교체. 현재 0.5%는 임시 값
    //       (docs/remittance/api-spec.md §4 참고). 정책 SSOT가 docs라 코드 상수 동기화 주의.
    private static final BigDecimal REMITTANCE_FEE_RATE = new BigDecimal("0.005");
    private static final int FEE_SCALE = 4;

    /** 수수료 API가 허용하는 송금 유형. TransactionType 중 CHARGE/EXCHANGE는 거부. */
    private static final Set<TransactionType> ALLOWED_TRANSFER_TYPES =
            EnumSet.of(TransactionType.INTERNAL_TRANSFER, TransactionType.REMITTANCE);

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
    private final ObjectMapper objectMapper;

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
                            .lastAmount(lastTx != null ? lastTx.getAmount().setScale(4).toPlainString() : null)
                            .lastTransferredAt(RecentAccountsResponse.toUtcZ(p.getLastTransferredAt()))
                            .build();
                })
                .toList();

        return RecentAccountsResponse.of(items);
    }

    @Override
    public AccountHolderResponse getAccountHolder(String bankCode, String accountNumber) {
        // DB 안 보고 외부 Mock 은행만 호출. 외부 에러는 BankErrorMapper가 BusinessException으로 변환해
        // 던지므로 (BANK4040→ACCOUNT4001, 네트워크 실패→COMMON5031 등) Service에서 try-catch 불필요.
        return AccountHolderResponse.from(bankClient.inquiry(bankCode, accountNumber));
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

        // 3) amount 파싱. @Pattern으로 형식 보장됨(양수 십진수, 소수 4자리 이내).
        BigDecimal amount = new BigDecimal(request.amount());

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
        // 1) 멱등성 Layer 1 — Redis 캐시 (가장 빠른 경로)
        Optional<TransferExecuteResponse> cached = readFromCache(idempotencyKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 2) 멱등성 Layer 2 — DB 조회 (캐시 미스/만료/장애 대비)
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            TransferExecuteResponse resp = TransferExecuteResponse.from(existing.get());
            writeToCache(idempotencyKey, resp); // Layer 1 채워두기
            return resp;
        }

        // 3) 입력 검증 — 형식은 @Valid에서, enum/도메인 검증은 여기서.
        CurrencyType currency = CurrencyType.fromCode(request.currencyCode())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));
        TransactionType transferType = TransactionType.fromCode(request.transferType())
                .filter(ALLOWED_TRANSFER_TYPES::contains)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE));

        // 4) 같은 통화만 허용 (2단계까지 same-currency 강제 — 다통화는 3단계).
        if (!request.currencyCode().equals(request.receiveCurrencyCode())) {
            throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY_PAIR);
        }

        // 5) REMITTANCE 분기 — 외부 계좌 송금은 receiver wallet/self-check/분산 락이 의미 없으므로
        //    별도 경로로 분리. INTERNAL_TRANSFER 경로는 손대지 않는다.
        if (transferType == TransactionType.REMITTANCE) {
            return executeRemittancePath(userPublicId, idempotencyKey, request, currency);
        }

        // 6) 송신/수신 wallet 조회 (락 키용 id 확보).
        Wallet senderWallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
        Wallet receiverWallet = walletRepository.findByUserPublicId(request.receiverPublicId())
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // 7) 자기 자신에게 송금 차단 (이후 MultiLock에서 같은 키 2회 잠금 문제 회피도 겸함).
        if (senderWallet.getId().equals(receiverWallet.getId())) {
            throw new BusinessException(TransferErrorCode.SELF_TRANSFER_NOT_ALLOWED);
        }

        // 8) 분산 락 획득 (wallet_id 오름차순 — DistributedLockHelper 내부 정책). 실패 → 503.
        RLock lock = distributedLockHelper.tryLockTwoWallets(
                senderWallet.getId(), receiverWallet.getId());
        if (lock == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            // 9) 트랜잭션 내 실제 처리 (self-proxy로 호출 — 직접 호출 시 @Transactional 미적용).
            TransferExecuteResponse response = self.executeInTransaction(
                    senderWallet.getId(), receiverWallet.getId(),
                    currency, transferType, idempotencyKey, request);

            // 10) 커밋 이후 Redis 캐시 채우기 (Layer 1 → 다음 동일 키 요청은 DB 미접근).
            writeToCache(idempotencyKey, response);
            return response;
        } finally {
            // 락 보유자가 본인인 경우에만 해제 (lease 만료로 다른 스레드가 가진 경우 안전).
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public TransferExecuteResponse executeInTransaction(
            Long senderWalletId, Long receiverWalletId,
            CurrencyType currency, TransactionType transferType,
            String idempotencyKey, TransferExecuteRequest request) {

        try {
            // REMITTANCE는 별도 in-tx 경로(외부 계좌 + 단일 송신자 잔액). INTERNAL_TRANSFER 코드는 그대로 유지.
            if (transferType == TransactionType.REMITTANCE) {
                return executeRemittanceInTransaction(senderWalletId, currency, idempotencyKey, request);
            }

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

        } catch (DataIntegrityViolationException race) {
            // 멱등성 Layer 3 — 동시 race로 다른 트랜잭션이 같은 idempotency_key를 먼저 커밋한 경우.
            // 이 트랜잭션은 UNIQUE 위반으로 롤백되며, 별도 트랜잭션(REQUIRES_NEW)에서 첫 결과를 재조회한다.
            return self.readPriorTransaction(idempotencyKey);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TransferExecuteResponse readPriorTransaction(String idempotencyKey) {
        Transaction prior = transactionRepository.findByIdempotencyKey(idempotencyKey)
                // race로 들어왔는데 키가 사라졌다면 일관성이 깨진 비정상 상태 → 서버 오류.
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        return TransferExecuteResponse.from(prior);
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
        // bank_account_public_id는 REMITTANCE 전용 필수 — DTO 레벨 @NotBlank가 아니므로 Service에서 검증.
        // INTERNAL_TRANSFER receiver_public_id는 DTO @NotBlank로 강제되므로 일관성 차원에서 같은 COMMON4001 매핑.
        String bankAccountPublicId = request.bankAccountPublicId();
        if (bankAccountPublicId == null || bankAccountPublicId.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }

        // 송신자 wallet 조회 (락 키용 id 확보). receiver/lock 없음.
        Wallet senderWallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // self-proxy로 호출 — 직접 호출 시 @Transactional 미적용(INTERNAL_TRANSFER 경로와 동일).
        // receiverWalletId는 null (외부 계좌). executeInTransaction이 transferType으로 분기.
        TransferExecuteResponse response = self.executeInTransaction(
                senderWallet.getId(), null, currency, TransactionType.REMITTANCE, idempotencyKey, request);

        // 커밋 이후 Redis 캐시 채우기 (Layer 1) — INTERNAL_TRANSFER 경로와 동일.
        writeToCache(idempotencyKey, response);
        return response;
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

        // (8) 잔액 차감 — dirty checking으로 UPDATE.
        BigDecimal senderBefore = senderBalance.getBalance();
        senderBalance.subtract(totalDeduct);
        BigDecimal senderAfter = senderBalance.getBalance();

        // (9) Transaction INSERT — idempotency_key UNIQUE 위반 시 상위 catch가 Layer 3 흐름으로 흡수.
        //     bank_account_id = 검증된 계좌의 내부 id (database.md REMITTANCE 행 — 수취 계좌 컬럼).
        //     receiver_wallet_id = null (외부 계좌). receiver_name = null (BankAccount에 holderName 필드 없음 —
        //       후속에 BankClient.inquiry로 받은 holder를 매핑할지 검토).
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
