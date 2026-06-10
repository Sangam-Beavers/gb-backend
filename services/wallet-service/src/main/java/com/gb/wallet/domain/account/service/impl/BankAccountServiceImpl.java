package com.gb.wallet.domain.account.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.ConfirmAccountRequest;
import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.ConfirmAccountResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.repository.BankRepository;
import com.gb.wallet.domain.account.service.BankAccountService;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.client.dto.VerifyInitResult;
import com.gb.wallet.global.config.VerifyRateLimitProperties;
import com.gb.wallet.global.event.MilestoneAchieved;
import com.gb.wallet.global.event.MilestoneType;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.redis.DistributedLockHelper;
import com.gb.wallet.global.redis.RateLimitHelper;
import com.gb.wallet.global.redis.VerifySessionStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankAccountServiceImpl implements BankAccountService {

    private static final String VERIFY_RATE_LIMIT_KEY_PREFIX = "ratelimit:account-verify:";
    private static final String REGISTER_LOCK_KEY_PREFIX     = "lock:account-register:";

    private final BankAccountRepository bankAccountRepository;
    private final BankRepository bankRepository;
    private final BankClient bankClient;
    private final RateLimitHelper rateLimitHelper;
    private final VerifyRateLimitProperties verifyRateLimitProperties;
    private final DistributedLockHelper distributedLockHelper;
    private final VerifySessionStore verifySessionStore;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * self-injection: *Locked 워커를 AOP 프록시를 통해 호출해 @Transactional이 적용되도록 한다.
     * @Lazy로 빈 생성 시점의 자기참조 순환을 끊는다.
     */
    @Autowired
    @Lazy
    private BankAccountService self;

    // ── 조회 ──────────────────────────────────────────────────────────────────

    @Override
    public AccountListResponse getMyAccounts(String userPublicId) {
        List<BankAccount> accounts = bankAccountRepository
                .findAllByUserPublicIdAndIsActiveTrueOrderByIsPrimaryDescCreatedAtDesc(userPublicId);
        return AccountListResponse.from(accounts);
    }

    // ── 인증 1단계: 1원 소액이체 요청 ─────────────────────────────────────────

    /**
     * Mock 은행에 1원 소액이체 인증을 요청한다(1단계).
     * account_token은 발급하지 않으며, confirmAccount(2단계)에서 코드 검증 후 발급된다.
     *
     * <p>사용자 단위 고정 윈도 rate-limit(WACC-02). Redis 장애 시 fail-open.
     */
    @Override
    public VerifyAccountResponse verifyAccount(VerifyAccountRequest request, String userPublicId) {
        boolean allowed = rateLimitHelper.tryAcquire(
                VERIFY_RATE_LIMIT_KEY_PREFIX + userPublicId,
                verifyRateLimitProperties.limit(),
                Duration.ofSeconds(verifyRateLimitProperties.windowSeconds()));
        if (!allowed) {
            throw new BusinessException(AccountErrorCode.VERIFICATION_RATE_LIMITED);
        }

        VerifyInitResult result = bankClient.initVerify(
                request.getBankCode(), request.getAccountNumber(), request.getHolderName());
        return VerifyAccountResponse.from(result);
    }

    // ── 인증 2단계: 코드 검증 → token 발급 → Redis 저장 ──────────────────────

    /**
     * 사용자가 입금 적요에서 확인한 4자리 코드를 검증하고 account_token을 Redis 세션에 저장한다.
     *
     * <p>Mock 은행 오류는 BankErrorMapper가 변환:
     * BANK4005(코드 불일치) → ACCOUNT4008, BANK4006(세션 없음/만료) → ACCOUNT4009.
     *
     * <p>token을 Redis에 저장 실패 시 fail-closed로 예외 전파(금융 정합성).
     */
    @Override
    public ConfirmAccountResponse confirmAccount(ConfirmAccountRequest request, String userPublicId) {
        AccountToken token = bankClient.confirmVerify(
                request.getBankCode(), request.getAccountNumber(), request.getCode());

        // 서버가 발급받은 token을 Redis에 단명(600초) 보관 — registerAccount가 여기서 소비.
        // fail-closed: 저장 실패 시 COMMON5000 전파(증표 없이 register 진행 차단).
        verifySessionStore.save(
                userPublicId, request.getBankCode(), request.getAccountNumber(), token.accountToken());

        return ConfirmAccountResponse.success();
    }

    // ── 계좌 등록 ──────────────────────────────────────────────────────────────

    /**
     * 계좌 등록 진입점. user 단위 분산락으로 race를 막은 뒤 registerAccountLocked에 위임.
     *
     * <p><b>F1</b> — 은행 코드 검증(findByCode)과 예금주명 조회(inquiry, 외부 HTTP)는
     * 락/트랜잭션 <b>밖</b>에서 먼저 수행한다. 락 lease(5s) < bank read-timeout(10s)이므로
     * inquiry를 락 안에서 호출하면 은행 지연 시 lease 만료 창에 동시 등록이 끼어 다중 주계좌가
     * 생길 수 있다(ACC1). critical section엔 DB read/write만 남긴다.
     *
     * <p>account_token은 직전 confirmAccount가 Redis에 저장한 값을 여기서 소비(GETDEL)한다.
     * 세션이 없으면 ACCOUNT4009 — confirmAccount를 먼저 완료해야 한다(WACC-05/charge-3).
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AccountResponse registerAccount(String userPublicId, RegisterAccountRequest request) {
        // 은행 코드 검증 (잘못된 bankCode는 inquiry 전에 끊는다 — F1)
        Bank bank = bankRepository.findByCode(request.getBankCode())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));

        // WACC-05 — 예금주명은 클라이언트 입력(request.getHolderName())이 아니라 은행 권위 값(inquiry).
        String holderName = bankClient.inquiry(request.getBankCode(), request.getAccountNumber())
                .accountHolderName();

        if (holderName == null || holderName.length() > 100) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        // WACC-05 / 10D wallet-account-charge-3 — 저장 토큰은 클라이언트 입력이 아니라
        // confirmAccount가 서버에서 발급받아 Redis에 저장한 값을 소비한다.
        // 세션이 없으면 ACCOUNT4009(인증을 먼저 완료해야 함).
        String accountToken = verifySessionStore.consume(userPublicId,
                request.getBankCode(), request.getAccountNumber());

        RLock lock = distributedLockHelper.tryLock(REGISTER_LOCK_KEY_PREFIX + userPublicId);
        if (lock == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return self.registerAccountLocked(userPublicId, request, bank, holderName, accountToken);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public AccountResponse registerAccountLocked(String userPublicId, RegisterAccountRequest request,
                                                 Bank bank, String holderName, String accountToken) {
        if (bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                userPublicId, request.getBankCode(), request.getAccountNumber())) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);
        }

        boolean isPrimary = bankAccountRepository.countByUserPublicIdAndIsActiveTrue(userPublicId) == 0;

        BankAccount account = BankAccount.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .bank(bank)
                .accountNumber(request.getAccountNumber())
                .holderName(holderName)
                .mockAccountToken(accountToken)
                .isVirtual(false)
                .isPrimary(isPrimary)
                .isActive(true)
                .build();

        try {
            BankAccount saved = bankAccountRepository.saveAndFlush(account);
            // Phase 2(BE-3) — 계좌 인증·등록 완료 마일스톤. 내부 이벤트만 발행하고, Kafka 전송은 본 tx
            // "커밋 후" MilestoneEventPublisher(AFTER_COMMIT)가 수행한다(롤백 시 미발행). 유저당 두 번째
            // 계좌여도 매번 발행 — 수신측(member)이 (user, milestone) UNIQUE로 자연 멱등 스킵(스파이크 결정 3).
            eventPublisher.publishEvent(
                    new MilestoneAchieved(userPublicId, MilestoneType.BANK_ACCOUNT_CONNECTED));
            return AccountResponse.from(saved);
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);
        }
    }

    // ── 주 계좌 변경 ───────────────────────────────────────────────────────────

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AccountResponse changePrimary(String userPublicId, String accountPublicId) {
        RLock lock = distributedLockHelper.tryLock(REGISTER_LOCK_KEY_PREFIX + userPublicId);
        if (lock == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return self.changePrimaryLocked(userPublicId, accountPublicId);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    @Override
    @Transactional
    public AccountResponse changePrimaryLocked(String userPublicId, String accountPublicId) {
        BankAccount target = bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(accountPublicId, userPublicId)
                .orElseThrow(() -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND));
        if (target.isPrimary()) return AccountResponse.from(target);
        bankAccountRepository.findByUserPublicIdAndIsPrimaryTrueAndIsActiveTrue(userPublicId)
                .ifPresent(BankAccount::releasePrimary);
        target.markAsPrimary();
        return AccountResponse.from(target);
    }

    // ── 계좌 삭제 ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deleteAccount(String userPublicId, String accountPublicId) {
        RLock lock = distributedLockHelper.tryLock(REGISTER_LOCK_KEY_PREFIX + userPublicId);
        if (lock == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            self.deleteAccountLocked(userPublicId, accountPublicId);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    @Override
    @Transactional
    public void deleteAccountLocked(String userPublicId, String accountPublicId) {
        BankAccount target = bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(accountPublicId, userPublicId)
                .orElseThrow(() -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND));
        boolean wasPrimary = target.isPrimary();
        target.deactivate();
        if (wasPrimary) {
            bankAccountRepository
                    .findFirstByUserPublicIdAndIsActiveTrueAndIdNotOrderByCreatedAtDesc(userPublicId, target.getId())
                    .ifPresent(BankAccount::markAsPrimary);
        }
    }
}
