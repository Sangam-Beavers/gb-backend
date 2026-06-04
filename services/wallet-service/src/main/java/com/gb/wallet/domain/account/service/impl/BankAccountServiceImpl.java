package com.gb.wallet.domain.account.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.repository.BankRepository;
import com.gb.wallet.domain.account.service.BankAccountService;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.config.VerifyRateLimitProperties;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.redis.DistributedLockHelper;
import com.gb.wallet.global.redis.RateLimitHelper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankAccountServiceImpl implements BankAccountService {

    /** 계좌 인증 rate-limit 카운터 키 prefix(사용자 단위 — WACC-02). database.md §7 등록. */
    private static final String VERIFY_RATE_LIMIT_KEY_PREFIX = "ratelimit:account-verify:";
    /**
     * 계좌 변경 직렬화 분산락 키 prefix(user 단위). database.md §7 등록. 상수 이름은 register지만 실제
     * 의미는 "user 단위 계좌 변경 락"이다 — 등록/주계좌 변경/삭제가 같은 키로 직렬화돼야 "주 계좌 1개"
     * 불변식이 동시성 race에서 깨지지 않는다(register/changePrimary/deleteAccount 공용).
     */
    private static final String REGISTER_LOCK_KEY_PREFIX = "lock:account-register:";

    private final BankAccountRepository bankAccountRepository;
    private final BankRepository bankRepository;
    private final BankClient bankClient;
    private final RateLimitHelper rateLimitHelper;
    private final VerifyRateLimitProperties verifyRateLimitProperties;
    private final DistributedLockHelper distributedLockHelper;

    /**
     * self-injection: 락을 잡은 외곽 메서드(register/changePrimary/deleteAccount)가 {@code *Locked} 워커를
     * AOP 프록시를 통해 호출해야 {@code @Transactional}이 적용되고 락이 커밋 시점까지 유지된다(같은 빈 내부의
     * {@code this.xxxLocked()}는 프록시를 우회해 트랜잭션이 안 걸림). {@code @Lazy}로 빈 생성 시점의 자기참조
     * 순환을 끊는다. 필드 주입을 쓴 이유는 {@code ChargeServiceImpl}의 동일 패턴 javadoc 참고.
     */
    @Autowired
    @Lazy
    private BankAccountService self;

    @Override
    public AccountListResponse getMyAccounts(String userPublicId) {
        List<BankAccount> accounts = bankAccountRepository
                .findAllByUserPublicIdAndIsActiveTrueOrderByIsPrimaryDescCreatedAtDesc(userPublicId);
        return AccountListResponse.from(accounts);
    }

    @Override
    public VerifyAccountResponse verifyAccount(VerifyAccountRequest request, String userPublicId) {
        // 사용자 단위 고정 윈도 rate-limit — 한 사용자의 외부 은행 인증 폭주를 막는다. 초과 시 ACCOUNT4005(429).
        // 위조 가능한 IP(XFF) 대신 위조불가 userPublicId로 키잉해 우회를 막는다(WACC-02).
        // Redis 장애 시 fail-open(통과) — rate-limit은 보안 보조 장치이고 verify는 저위험(외부 호출만, DB 미사용).
        boolean allowed = rateLimitHelper.tryAcquire(
                VERIFY_RATE_LIMIT_KEY_PREFIX + userPublicId,
                verifyRateLimitProperties.limit(),
                Duration.ofSeconds(verifyRateLimitProperties.windowSeconds()));
        if (!allowed) {
            throw new BusinessException(AccountErrorCode.VERIFICATION_RATE_LIMITED);
        }

        // Mock 은행 실패는 BankErrorMapper가 BusinessException으로 변환해 던지므로 그대로 전파한다.
        AccountToken token = bankClient.verify(
                request.getBankCode(), request.getAccountNumber(), request.getHolderName());
        return VerifyAccountResponse.from(token);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AccountResponse registerAccount(String userPublicId, RegisterAccountRequest request) {
        // 등록을 user 단위로 직렬화(분산락) — 동시 등록 race에서 중복 계좌·다중 주계좌가 생기는 것을 차단한다.
        // 락은 트랜잭션 "밖"(NOT_SUPPORTED)에서 잡고, 실제 INSERT는 self-proxy(registerAccountLocked,
        // @Transactional)로 호출해 락이 커밋 시점까지 유지되도록 한다(TransferServiceImpl.execute와 동일 구조).
        //
        // F1 — 은행 코드 검증(findByCode)과 예금주명 조회(inquiry, 동기 HTTP)는 락/트랜잭션 "밖"에서 먼저 한다.
        //   락 lease=5s(watchdog 없음) < bank read-timeout=10s 이므로, inquiry를 락 안에서 호출하면 은행 지연 시
        //   lease 만료 창에 같은 user의 2번째 등록이 끼어 다중 주계좌가 생길 수 있다(ACC1 회귀). critical section엔
        //   DB write만 남긴다. 부수로 잘못된 bank_code는 inquiry(은행 호출) 전에 COMMON4001로 끊긴다.
        Bank bank = bankRepository.findByCode(request.getBankCode())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));

        // WACC-05 — 예금주명은 클라이언트 입력(request.getHolderName())이 아니라 은행 권위 값(inquiry)을 쓴다.
        //   클라가 verify는 진짜 이름으로 통과시키고 register엔 다른 이름을 보내 송금 확인증(receiver_name)을
        //   위조하는 것을 막는다. (request.holderName 필드는 호환 위해 남기되 신뢰하지 않는다 — vestigial.)
        String holderName = bankClient.inquiry(request.getBankCode(), request.getAccountNumber())
                .accountHolderName();

        RLock lock = distributedLockHelper.tryLock(REGISTER_LOCK_KEY_PREFIX + userPublicId);
        if (lock == null) {
            // 락 획득 실패 → 503(fail-closed): "잠깐 거부"가 "조용히 중복 생성"보다 안전.
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return self.registerAccountLocked(userPublicId, request, bank, holderName);
        } finally {
            // 락 보유자가 본인인 경우에만 해제(lease 만료로 다른 스레드가 가진 경우 안전).
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public AccountResponse registerAccountLocked(String userPublicId, RegisterAccountRequest request,
                                                 Bank bank, String holderName) {
        // 1차 방어: 활성 중복 계좌 선검사(흔한 경로를 깔끔히 ACCOUNT4004로). 동시성 최종 안전망은 아래 (user,
        //   bank, account_number) 부분 UNIQUE(prod, WACC-06)와 분산락이 함께 담당한다.
        if (bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                userPublicId, request.getBankCode(), request.getAccountNumber())) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);
        }

        // bank(은행 코드 검증)·holderName(은행 권위 예금주명)은 호출자(registerAccount)가 락/트랜잭션 밖에서
        //   미리 확정해 넘긴다(F1 — ACC1 회귀 차단). 이 메서드의 critical section엔 DB read/write만 둔다.

        // 사용자의 첫 활성 계좌면 자동으로 주 계좌. 그 외에는 항상 false로 둔다 — 등록 흐름에서 다중
        // 주 계좌(같은 사용자에 활성 is_primary=true 둘 이상)가 발생하면 충전 시 어느 계좌가 기본인지
        // 모호해진다. 명세 §11은 주 계좌 변경을 PATCH /api/v1/accounts/{id}/primary로 분리해두었으므로
        // register는 변경 책임을 갖지 않는다.
        boolean isPrimary = bankAccountRepository.countByUserPublicIdAndIsActiveTrue(userPublicId) == 0;

        BankAccount account = BankAccount.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .bank(bank)
                .accountNumber(request.getAccountNumber())
                .holderName(holderName)
                .mockAccountToken(request.getAccountToken())
                .isVirtual(false)
                .isPrimary(isPrimary)
                .isActive(true)
                .build();

        // WACC-06 — saveAndFlush로 prod의 (user_public_id, bank_id, account_number) 부분 UNIQUE 위반을 이 메서드
        //   안에서 잡는다. 분산락 lease 만료/split-brain로 선검사를 통과한 동시 등록이 빠져나가도 DB 제약이
        //   최종 안전망이며, 위반은 ACCOUNT4004로 매핑한다(부분 UNIQUE는 active 행만 — 삭제 계좌 재등록 허용.
        //   DDL은 database.md 참조. dev/H2는 생성컬럼 미생성이라 본 catch는 prod에서만 실효).
        //   이 saveAndFlush에서 발생 가능한 무결성 위반은 위 부분 UNIQUE(uk_bank_accounts_active_acct)뿐이다 —
        //   FK(bank)·NOT NULL·holderName은 상위에서 모두 확정·검증된다. 따라서 제약명(getConstraintName)으로
        //   골라내지 않고 contextual하게 좁혀 잡는다(제약명 판별은 prod-only·null 가능이라 비이식적, CMN 정합).
        //   (만약 미상의 위반이라면 rethrow돼 중앙 핸들러가 500 COMMON5000으로 처리한다.)
        try {
            BankAccount saved = bankAccountRepository.saveAndFlush(account);
            return AccountResponse.from(saved);
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AccountResponse changePrimary(String userPublicId, String accountPublicId) {
        // 등록/변경/삭제 공용 락으로 직렬화 — 동시 변경 race에서 다중 주계좌(활성 is_primary 둘 이상)를 차단.
        // 락은 트랜잭션 "밖"(NOT_SUPPORTED)에서 잡고, 실제 UPDATE는 self-proxy(changePrimaryLocked,
        // @Transactional)로 호출해 락이 커밋 시점까지 유지되도록 한다(register와 동일 구조).
        RLock lock = distributedLockHelper.tryLock(REGISTER_LOCK_KEY_PREFIX + userPublicId);
        if (lock == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return self.changePrimaryLocked(userPublicId, accountPublicId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public AccountResponse changePrimaryLocked(String userPublicId, String accountPublicId) {
        BankAccount target = bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(accountPublicId, userPublicId)
                .orElseThrow(() -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND));
        // 이미 주 계좌면 멱등 성공 — 기존 주계좌 해제 finder도 타지 않고 그대로 반환한다.
        if (target.isPrimary()) {
            return AccountResponse.from(target);
        }
        // 기존 주 계좌(불변식상 최대 1건)를 해제한 뒤 대상을 주 계좌로 승격. dirty checking으로 flush.
        bankAccountRepository.findByUserPublicIdAndIsPrimaryTrueAndIsActiveTrue(userPublicId)
                .ifPresent(BankAccount::releasePrimary);
        target.markAsPrimary();
        return AccountResponse.from(target);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deleteAccount(String userPublicId, String accountPublicId) {
        // 등록/변경/삭제 공용 락으로 직렬화 — 주계좌 삭제+자동 승격이 동시 변경과 엉켜 다중 주계좌가 되는 것을 차단.
        RLock lock = distributedLockHelper.tryLock(REGISTER_LOCK_KEY_PREFIX + userPublicId);
        if (lock == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            self.deleteAccountLocked(userPublicId, accountPublicId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public void deleteAccountLocked(String userPublicId, String accountPublicId) {
        BankAccount target = bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(accountPublicId, userPublicId)
                .orElseThrow(() -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND));
        boolean wasPrimary = target.isPrimary();
        target.deactivate(); // soft-delete
        // 주 계좌를 삭제했으면 남은 활성 계좌 중 가장 최근 1건을 자동 승격. 남은 계좌가 없으면(마지막 계좌)
        // 그대로 둔다 — 주 계좌 없는 상태를 허용한다(충전은 {id} path로 출금 계좌를 명시받으므로 무관).
        if (wasPrimary) {
            bankAccountRepository
                    .findFirstByUserPublicIdAndIsActiveTrueAndIdNotOrderByCreatedAtDesc(userPublicId, target.getId())
                    .ifPresent(BankAccount::markAsPrimary);
        }
    }
}
