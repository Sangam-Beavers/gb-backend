package com.gb.wallet.domain.account.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.ChargeRequest;
import com.gb.wallet.domain.account.dto.response.ChargeResponse;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.service.ChargeService;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.domain.wallet.service.WalletBalanceWriter;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChargeServiceImpl implements ChargeService {

    /** 충전 통화는 KRW 고정(명세 §12, §5-4). 외화 충전은 본 작업 범위 밖. */
    private static final CurrencyType CHARGE_CURRENCY = CurrencyType.KRW;

    /** audit_log.action 값. */
    private static final String CHARGE_ACTION = "CHARGE";

    /** Mock 은행이 정상 처리했을 때 돌려주는 상태값. */
    private static final String COMPLETED_STATUS = "COMPLETED";

    /**
     * 단일 거래 충전 한도(명세 §12 ACCOUNT4007). 운영 정책 확정 전 임시값 1천만원.
     * TODO: 운영 정책 확정 후 사용자/일/월 누적 한도를 포함한 정책 객체 또는 외부 설정으로 분리한다.
     */
    private static final BigDecimal SINGLE_CHARGE_LIMIT = new BigDecimal("10000000");

    private final BankAccountRepository bankAccountRepository;
    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final WalletBalanceWriter walletBalanceWriter;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditLogRepository auditLogRepository;
    private final BankClient bankClient;

    /**
     * self-injection: {@code @Transactional}이 적용되려면 {@link #doCharge}/{@link #readPrior}를 AOP
     * 프록시를 통해 호출해야 한다(같은 빈 내부의 {@code this.doCharge()}는 프록시를 우회해 트랜잭션이 안 걸림).
     * {@code @Lazy}로 빈 생성 시점의 자기참조 순환을 끊는다.
     *
     * <p><b>필드 주입을 쓴 이유:</b> 이 프로젝트엔 {@code lombok.config}가 없어 {@code @RequiredArgsConstructor}가
     * 필드의 {@code @Lazy}를 생성자 파라미터로 복사하지 않는다(copyableAnnotations 미설정). 그래서
     * {@code @Lazy private final ChargeService self} + 생성자 주입 형태는 자기참조 순환(BeanCurrentlyInCreationException)을
     * 일으킨다. 자기참조 한정으로 필드 주입({@code @Autowired @Lazy})을 쓰는 것이 표준적이고 안전하다.
     */
    @Autowired
    @Lazy
    private ChargeService self;

    @Override
    public ChargeResponse charge(String userPublicId, String accountPublicId,
                                 String idempotencyKey, ChargeRequest request, String clientIp) {
        try {
            return self.doCharge(userPublicId, accountPublicId, idempotencyKey, request, clientIp);
        } catch (DataIntegrityViolationException race) {
            // 동시 충전 race: 다른 트랜잭션이 같은 idempotency_key로 먼저 커밋했다(UNIQUE 위반).
            // IDENTITY 전략이라 INSERT가 save() 시점에 즉시 실행되므로 위반은 doCharge 트랜잭션 "안"에서
            // 발생하고, 커밋 시점이 아니라 그 전에 예외로 떠오른다. doCharge 트랜잭션은 rollback-only로
            // 마킹돼 같은 트랜잭션 내 재조회가 불가하므로, 트랜잭션 밖인 여기서 잡아 별도 트랜잭션으로
            // 첫 결과를 재조회한다(§5-1).
            //
            // NOTE(후속 하드닝): 매우 높은 동시성에서 같은 잔액 행 비관적 락 경합이 겹치면 InnoDB가 위반을
            //   DataIntegrityViolationException이 아니라 형제 타입(CannotAcquireLockException /
            //   DeadlockLoserDataAccessException = PessimisticLockingFailureException 계열)으로 표면화할 수
            //   있다. 이 경우 현재 catch가 놓쳐 COMMON5031/5000으로 응답될 수 있다. Mock은 같은 키로 첫 응답을
            //   재반환하므로 외부 이중 차감은 없고(§5-2), 클라이언트 재시도로 정합성은 회복되지만, 운영 전환 시
            //   catch를 PessimisticLockingFailureException까지 넓히고 bounded retry를 더하는 것을 검토한다.
            return self.readPrior(idempotencyKey, accountPublicId, userPublicId);
        }
    }

    @Override
    @Transactional
    public ChargeResponse doCharge(String userPublicId, String accountPublicId,
                                   String idempotencyKey, ChargeRequest request, String clientIp) {

        // (1) 멱등성 선검사: 이미 처리된 키면 Mock 호출·잔액 변경 없이 첫 결과를 재반환한다.
        Optional<Transaction> prior = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) {
            return rebuildFromPrior(prior.get(), accountPublicId, userPublicId);
        }

        // (2) 계좌 조회 — 본인 + 활성 + 존재. 셋 중 하나라도 어긋나면 ACCOUNT4001(사유 미구분 — 정보 누설 방지).
        BankAccount account = bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(accountPublicId, userPublicId)
                .orElseThrow(() -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND));

        // (3) 토큰(인증) 확인 — 없으면 Mock 호출 전에 ACCOUNT4006으로 차단.
        if (account.getMockAccountToken() == null) {
            throw new BusinessException(AccountErrorCode.UNVERIFIED_ACCOUNT);
        }

        // (4) 한도 검증 — 초과면 ACCOUNT4007. 음수/0/형식 오류는 컨트롤러 @Valid 단계에서 COMMON4001로 이미 차단됨.
        BigDecimal amount = request.getAmount();
        if (amount.compareTo(SINGLE_CHARGE_LIMIT) > 0) {
            throw new BusinessException(AccountErrorCode.CHARGE_LIMIT_EXCEEDED);
        }

        // (5) 지갑 조회 — 없으면 WALLET4001.
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // (6) Mock 은행 출금. 실패는 BankErrorMapper가 BusinessException으로 변환해 던지므로 그대로 전파한다.
        //     idempotencyKey를 그대로 forward — Mock도 같은 키로 첫 응답을 재반환한다(§5-2).
        WithdrawalResult mockResult = bankClient.withdraw(
                account.getMockAccountToken(), amount, CHARGE_CURRENCY.name(), idempotencyKey);
        // 방어 — 응답이 null이거나 COMPLETED가 아닌 비정상 케이스는 일시 장애(503)로 본다. 현재 구현체
        //     (MockBankClient)는 null 대신 예외를 던지지만, 향후 구현체가 null을 줘도 NPE→500이 아니라 503으로 매핑한다.
        if (mockResult == null || !COMPLETED_STATUS.equals(mockResult.status())) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }

        // (7) 잔액 행을 비관적 락으로 조회. 없으면(첫 KRW 충전) 먼저 0원 행을 보장한 뒤 다시 잠근다(§5-5).
        //     존재하지 않는 행은 FOR UPDATE로 잠글 수 없어, 여기서 단순 save하면 동시 첫 충전(서로 다른 키)에서
        //     uk_wallet_balances_wallet_currency 위반이 메인 트랜잭션을 오염시키고 charge() 래퍼의
        //     DataIntegrityViolationException catch(멱등성 race 복구)로 잘못 흘러가 COMMON5000이 된다.
        //     행 보장을 별도 트랜잭션(WalletBalanceWriter, REQUIRES_NEW)으로 분리해 위반을 거기서 흡수하고,
        //     본 트랜잭션은 항상 존재하는 행을 잠근다 — 그 catch는 이제 idempotency_key 위반만 본다.
        WalletBalance balance = walletBalanceRepository
                .findForUpdateByWalletAndCurrency(wallet, CHARGE_CURRENCY)
                .orElseGet(() -> {
                    walletBalanceWriter.ensureBalanceRow(wallet, CHARGE_CURRENCY);
                    return walletBalanceRepository
                            .findForUpdateByWalletAndCurrency(wallet, CHARGE_CURRENCY)
                            // 행 보장 직후라 비어 있을 수 없다 — 비면 정합성이 깨진 비정상 상태.
                            .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
                });

        BigDecimal beforeBalance = balance.getBalance();
        balance.addBalance(amount); // 영속 상태 → dirty checking으로 UPDATE
        BigDecimal afterBalance = balance.getBalance();

        // (8) 충전 거래 INSERT. idempotency_key UNIQUE 위반 시 여기서 DataIntegrityViolationException이
        //     떨어지며(IDENTITY라 save 시 즉시 INSERT), charge() 래퍼가 잡아 readPrior로 귀결된다.
        Transaction tx = transactionRepository.save(Transaction.builder()
                .publicId(UUID.randomUUID().toString())
                .wallet(wallet)
                .type(TransactionType.CHARGE)
                .amount(amount)
                .currencyCode(CHARGE_CURRENCY)
                .fee(BigDecimal.ZERO)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(idempotencyKey)
                .bankAccountId(account.getId()) // CHARGE의 출금 계좌(database.md 유형별 사용 컬럼)
                .build());

        // (9) 감사 로그 INSERT(append-only).
        auditLogRepository.save(TransactionAuditLog.builder()
                .transaction(tx)
                .userPublicId(userPublicId)
                .action(CHARGE_ACTION)
                .amount(amount)
                .currencyCode(CHARGE_CURRENCY)
                .beforeBalance(beforeBalance)
                .afterBalance(afterBalance)
                .status(TransactionStatus.COMPLETED)
                .ipAddress(clientIp)
                .build());

        return ChargeResponse.of(tx, accountPublicId, afterBalance);
    }

    @Override
    @Transactional(readOnly = true)
    public ChargeResponse readPrior(String idempotencyKey, String accountPublicId, String userPublicId) {
        Transaction prior = transactionRepository.findByIdempotencyKey(idempotencyKey)
                // race로 들어왔는데 키가 사라졌다면 일관성이 깨진 비정상 상태 → 서버 오류.
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        return rebuildFromPrior(prior, accountPublicId, userPublicId);
    }

    /**
     * 이미 처리된 거래로부터 첫 응답을 재구성한다(멱등성 재반환).
     *
     * <p>{@code idempotency_key}는 전역 UNIQUE(유형·사용자·계좌 무관)라, 같은 키로 다른 사용자/유형/계좌의
     * 거래가 잡힐 수 있다. 이 충전 요청의 응답으로 재현해도 되는 거래인지 다음을 확인하고, 아니면
     * ACCOUNT4001로 차단한다(존재 여부 미노출):
     * <ul>
     *   <li>키 소유자(거래 지갑 주인) == 요청자 — 타인이 남의 응답을 훔쳐보지 못하게</li>
     *   <li>거래 유형 == CHARGE — 송금/환전 등 다른 유형을 충전 응답으로 재현하지 않게</li>
     *   <li>거래의 출금 계좌 == 요청 계좌 — 같은 키를 다른 계좌로 재사용해 엉뚱한 계좌 응답이 나가지 않게.
     *       {@code Transaction}은 계좌의 {@code public_id}가 없고 {@code bankAccountId}(내부 id)만 들고 있어,
     *       그 id로 계좌를 풀어 {@code public_id}를 비교한다.</li>
     * </ul>
     *
     * <p>{@code wallet_balance}는 "충전 후 지갑 잔액"이라 <em>현재</em> 잔액이 아니라 최초 처리 당시의
     * {@code after_balance}(audit_log)를 돌려준다 — 두 번째 요청 시점엔 다른 거래로 잔액이 변했을 수 있다.
     */
    private ChargeResponse rebuildFromPrior(Transaction prior, String accountPublicId, String userPublicId) {
        if (!prior.getWallet().getUserPublicId().equals(userPublicId)
                || prior.getType() != TransactionType.CHARGE) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND);
        }
        // CHARGE는 출금 계좌 FK(bankAccountId)가 항상 있으므로 없으면 정합성이 깨진 비정상 상태.
        BankAccount priorAccount = bankAccountRepository.findById(prior.getBankAccountId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        if (!priorAccount.getPublicId().equals(accountPublicId)) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND);
        }
        TransactionAuditLog log = auditLogRepository
                .findFirstByTransaction_IdOrderByIdAsc(prior.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        return ChargeResponse.of(prior, accountPublicId, log.getAfterBalance());
    }
}