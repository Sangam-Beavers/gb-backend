package com.gb.wallet.domain.account.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.ChargeRequest;
import com.gb.wallet.domain.account.dto.response.ChargeResponse;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.service.ChargeAttemptWriter;
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
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.common.util.BestEffortRequiresNew;
import com.gb.wallet.global.config.ChargeProperties;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.IdempotencyCacheHelper;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeServiceImpl implements ChargeService {

    /** 충전 통화는 KRW 고정(명세 §12). 외화 충전은 미지원. */
    private static final CurrencyType CHARGE_CURRENCY = CurrencyType.KRW;

    /** audit_log.action 값. */
    private static final String CHARGE_ACTION = "CHARGE";

    /** Mock 은행이 정상 처리했을 때 돌려주는 상태값. */
    private static final String COMPLETED_STATUS = "COMPLETED";

    /** 충전 락 경합(데드락·락 타임아웃)으로 트랜잭션이 롤백됐을 때 doCharge 재시도 최대 횟수. 소진 시 COMMON5031. */
    private static final int MAX_CHARGE_ATTEMPTS = 3;

    private final BankAccountRepository bankAccountRepository;
    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final WalletBalanceWriter walletBalanceWriter;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditLogRepository auditLogRepository;
    private final BankClient bankClient;
    private final ChargeAttemptWriter chargeAttemptWriter;
    private final ChargeProperties chargeProperties;
    private final IdempotencyCacheHelper idempotencyCacheHelper;
    private final ObjectMapper objectMapper;

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
        // 멱등성 Layer 1 — Redis 캐시(가장 빠른 경로). hit이면 DB·Mock 호출 없이 첫 응답을 즉시 재반환한다.
        // 캐시 키를 (요청자, 계좌, 충전 도메인) 단위로 스코프(cacheKey)한다 — 같은 idempotency_key라도 다른
        // 사용자/계좌는 캐시 hit이 나지 않고 캐시 미스 → DB 경로(doCharge → rebuildFromPrior)로 흘러
        // ACCOUNT4001로 막힌다. 즉 Layer 1은 동일 (user, account, key)의 정상 재요청만 가속하고, 교차
        // 사용자/계좌/유형 도용을 우회시키지 않는다(DB Layer 2·3의 보안 검증과 동일한 불변식 유지). 또한
        // 멱등 캐시 prefix(idempotency:)를 송금과 공유하므로, charge 네임스페이스로 분리해 교차 도메인
        // 역직렬화(송금 응답을 충전 응답으로)도 차단한다. 캐시 실패는 응답을 막지 않는다 — Layer 2·3가 안전망.
        String cacheKey = cacheKey(idempotencyKey, userPublicId, accountPublicId);
        Optional<ChargeResponse> cached = readFromCache(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        ChargeResponse response = doChargeWithRetry(
                userPublicId, accountPublicId, idempotencyKey, request, clientIp);

        // 정상/race 복구 응답을 Layer 1에 채운다(다음 동일 (user, account, key) 요청은 DB·Mock 미접근).
        // 쓰기 실패는 응답에 영향 없음. 에러(BusinessException)는 위 흐름에서 전파돼 캐시에 들어가지 않는다.
        writeToCache(cacheKey, response);
        return response;
    }

    /**
     * 충전 본 처리({@link #doCharge}) 실행 + 동시성 예외 복구. 두 종류의 동시성 충돌을 구분해 처리한다.
     *
     * <ul>
     *   <li><b>idempotency_key UNIQUE 위반</b>({@link DataIntegrityViolationException}): 다른 트랜잭션이 같은
     *       키로 먼저 커밋했다. IDENTITY 전략이라 INSERT가 save() 시점에 즉시 실행돼 위반이 doCharge 트랜잭션
     *       "안"에서 떠오르고, 그 트랜잭션은 rollback-only라 같은 트랜잭션 내 재조회가 불가하다. 트랜잭션 밖인
     *       여기서 잡아 별도 트랜잭션({@link #readPrior})으로 첫 결과를 재반환한다(결과가 이미 존재 — 재시도 불필요).</li>
     *   <li><b>락 경합·데드락</b>({@link PessimisticLockingFailureException} = CannotAcquireLockException /
     *       DeadlockLoserDataAccessException): 같은 잔액 행을 다른 거래(다른 키의 충전·환전 등)와 비관적 락으로
     *       동시에 다투면 InnoDB가 패자 트랜잭션을 롤백한다. 일시적 충돌이므로 최대 {@link #MAX_CHARGE_ATTEMPTS}회
     *       재시도한다 — 재시도 첫 단계(doCharge 멱등 선검사)가 그새 커밋된 첫 결과를 잡으면 멱등 재반환되고,
     *       경합 상대가 끝났으면 정상 처리된다. 재시도 소진 시 일시 장애로 보고 {@code COMMON5031}(503).
     *       <br>⚠️ 재시도는 doCharge "전체" 재실행이라 (6) {@code bankClient.withdraw}도 같은 멱등키로
     *       재호출된다(직전 시도의 withdraw가 성공했어도 — 락 경합은 그 뒤 (7) FOR UPDATE에서 터지므로).
     *       이중출금은 은행 측 키 dedup(같은 키 → 첫 응답 재반환, remittance api-spec §13 계약)이 막으며
     *       로컬 측 선차감 가드는 두지 않는다 — 도입 시 인터페이스/정합성 모델 변경이라
     *       WTX-03 saga 재설계와 함께 검토). 은행 구현체 교체 시 이 dedup 계약 유지가 전제 조건.</li>
     * </ul>
     */
    private ChargeResponse doChargeWithRetry(String userPublicId, String accountPublicId,
                                             String idempotencyKey, ChargeRequest request, String clientIp) {
        int attempt = 0;
        while (true) {
            try {
                return self.doCharge(userPublicId, accountPublicId, idempotencyKey, request, clientIp);
            } catch (DataIntegrityViolationException race) {
                return self.readPrior(idempotencyKey, accountPublicId, userPublicId);
            } catch (PessimisticLockingFailureException lockContention) {
                if (++attempt >= MAX_CHARGE_ATTEMPTS) {
                    log.warn("충전 락 경합 재시도 소진({}회) — COMMON5031 매핑. account={}",
                            MAX_CHARGE_ATTEMPTS, accountPublicId, lockContention);
                    throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, lockContention);
                }
                log.debug("충전 락 경합 — 재시도 {}/{}. account={}", attempt, MAX_CHARGE_ATTEMPTS, accountPublicId);
            }
        }
    }

    /**
     * 충전 멱등성 Layer 1 캐시 키. {@code idempotency_key}를 (요청자, 계좌, 충전 도메인) 단위로 스코프해,
     * 교차 사용자/계좌/도메인 요청이 같은 {@code idempotency_key}로 캐시 hit을 일으키지 못하게 한다(그 경우
     * 캐시 미스 → DB 경로의 {@code rebuildFromPrior}가 ACCOUNT4001로 차단). {@code IdempotencyCacheHelper}가
     * {@code idempotency:} prefix를 덧붙이므로 최종 키는 {@code idempotency:charge:{key}:{user}:{account}}다.
     */
    private static String cacheKey(String idempotencyKey, String userPublicId, String accountPublicId) {
        return "charge:" + idempotencyKey + ":" + userPublicId + ":" + accountPublicId;
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
        if (amount.compareTo(chargeProperties.singleLimit()) > 0) {
            throw new BusinessException(AccountErrorCode.CHARGE_LIMIT_EXCEEDED);
        }

        // (5) 지갑 조회 — 없으면 WALLET4001.
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // (5.5) WTX-05 — 동결(SUSPENDED)/폐쇄(CLOSED) 지갑은 충전 차단(ACTIVE만 허용). WALLET4003(422).
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BusinessException(WalletErrorCode.WALLET_INACTIVE);
        }

        // (6.5) 외부 호출 *직전* 시도 흔적을 REQUIRES_NEW로 별도 커밋(WACC-01) — withdraw가 외부 성공 후
        //       메인 tx가 (비재시도성) 롤백/타임아웃돼도 흔적은 남아 운영 reconcile 입력이 된다(송금 payout의
        //       RemittanceAttemptWriter와 대칭). 같은 idempotency_key 재시도/race는 Writer 내부 UNIQUE 흡수로 1행 유지.
        // 동시 같은 키 race로 REQUIRES_NEW가 rollback-only가 되면 UnexpectedRollbackException이 전파되는데,
        // 흔적은 이미 존재하므로 흡수하고 진행한다(charge-2 — 정상 충전이 generic 500으로 깨지지 않게).
        BestEffortRequiresNew.run(() ->
                chargeAttemptWriter.record(idempotencyKey, userPublicId, account.getId(), amount, CHARGE_CURRENCY));

        // (6) Mock 은행 출금. 실패는 BankErrorMapper가 BusinessException으로 변환해 던지므로 그대로 전파한다.
        //     idempotencyKey를 그대로 forward — Mock도 같은 키로 첫 응답을 재반환한다.
        //
        //     ⚠️ 알려진 한계(WTX-03, wallet-account-charge-1 — REMITTANCE payout과 동일 사상): 이 withdraw(HTTP)는
        //        본 doCharge @Transactional *안*에서 호출되므로, 외부 호출이 끝날 때(최대 bank.api.read-timeout,
        //        기본 10s)까지 DB 커넥션을 점유한다. 동시 첫 충전이 많고 Mock 은행이 느리면 HikariCP 풀이 소진돼
        //        충전 외 쿼리까지 막힐 수 있다. payout을 tx 밖 짧은 별도 tx로 빼면(reserve→withdraw→confirm Saga)
        //        점유는 줄지만, 외부 성공 후 로컬 증액 실패 시 외부만 빠져나가는 정합성 창과 보상(환불) 로직이
        //        새로 필요해 정합성 모델이 바뀐다 — 팀 합의 + 진짜 MySQL(Testcontainers) 검증 선행이라 본 사이클
        //        범위 밖으로 연기한다(REMITTANCE WTX-03와 함께 가야 charge≡remittance 대칭 유지). 외부 성공 흔적은
        //        (6.5) charge_attempts로 이미 보존(reconcile 입력). prod 운영 권고: bank read-timeout 하향 +
        //        spring.datasource.hikari.maximum-pool-size 명시로 blast radius를 환경별로 캡할 것.
        WithdrawalResult mockResult = bankClient.withdraw(
                account.getMockAccountToken(), amount, CHARGE_CURRENCY.name(), idempotencyKey);
        // 방어 — 응답이 null이거나 COMPLETED가 아닌 비정상 케이스는 일시 장애(503)로 본다. 현재 구현체
        //     (MockBankClient)는 null 대신 예외를 던지지만, 향후 구현체가 null을 줘도 NPE→500이 아니라 503으로 매핑한다.
        if (mockResult == null || !COMPLETED_STATUS.equals(mockResult.status())) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        // (6-2) WTX-09 — 은행이 처리한 금액·통화가 요청과 일치하는지 대조한다(status만 믿지 않음). 불일치는
        //       부분처리/통화오류 등 정합성 깨짐이라 잔액에 반영하지 않고 일시 장애(503)로 보류한다(reconcile 대상).
        if (mockResult.amount() == null || mockResult.amount().compareTo(amount) != 0
                || !CHARGE_CURRENCY.name().equals(mockResult.currencyCode())) {
            log.error("충전 은행 응답 금액/통화 불일치 — 요청 {}{} vs 응답 {}{}. key={}",
                    amount, CHARGE_CURRENCY.name(), mockResult.amount(), mockResult.currencyCode(), idempotencyKey);
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }

        // (7) 잔액 행 0원 보장(REQUIRES_NEW)을 FOR UPDATE보다 "먼저" 한다.
        //     존재하지 않는 행에 FOR UPDATE를 걸면 MySQL(REPEATABLE READ)이 그 (wallet_id, currency) 자리에
        //     gap lock을 잡는데, 이어서 ensureBalanceRow의 REQUIRES_NEW INSERT(별도 커넥션)가 그 gap을
        //     기다리다 self-deadlock에 빠진다 — 바깥 트랜잭션은 INSERT가 끝나길 기다리고, 그 INSERT는 바깥이
        //     쥔 gap lock이 풀리길 기다려, InnoDB 데드락 감지에도 안 잡히고 innodb_lock_wait_timeout(기본 50s)을
        //     꽉 채운 뒤 PessimisticLockingFailureException으로 터진다(첫 충전마다 결정적 — H2는 gap lock이 없어
        //     테스트에서 안 드러남). 그래서 행을 먼저 독립 커밋(REQUIRES_NEW)해 만들어 두고 — 동시 첫 충전의
        //     uk_wallet_balances_wallet_currency 위반은 WalletBalanceWriter가 흡수 — 항상 존재하는 행을
        //     FOR UPDATE로 record lock한다. 환전(ExchangeServiceImpl)도 동일하게 ensure→FOR UPDATE 순서다.
        BestEffortRequiresNew.run(() -> walletBalanceWriter.ensureBalanceRow(wallet, CHARGE_CURRENCY)); // charge-2
        WalletBalance balance = walletBalanceRepository
                .findForUpdateByWalletAndCurrency(wallet, CHARGE_CURRENCY)
                // 행 보장 직후라 비어 있을 수 없다 — 비면 정합성이 깨진 비정상 상태.
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));

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

    // ----- Redis 캐시 헬퍼(멱등성 Layer 1): 캐시 실패는 응답을 막지 않는다 — TransferServiceImpl와 동일 패턴 -----

    private Optional<ChargeResponse> readFromCache(String cacheKey) {
        try {
            return idempotencyCacheHelper.get(cacheKey).flatMap(json -> {
                try {
                    return Optional.of(objectMapper.readValue(json, ChargeResponse.class));
                } catch (JsonProcessingException e) {
                    log.warn("Idempotency cache JSON 역직렬화 실패 — Layer 2로 폴백. key={}", cacheKey, e);
                    return Optional.empty();
                }
            });
        } catch (RuntimeException e) {
            log.warn("Redis 캐시 조회 실패 — Layer 2로 폴백. key={}", cacheKey, e);
            return Optional.empty();
        }
    }

    private void writeToCache(String cacheKey, ChargeResponse response) {
        try {
            idempotencyCacheHelper.set(cacheKey, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException | RuntimeException e) {
            // 캐시 쓰기 실패는 응답에 영향 없음 — 다음 동일 (user, account, key) 요청 시 Layer 2(DB)가 받아준다.
            log.warn("Idempotency cache 저장 실패. key={}", cacheKey, e);
        }
    }
}