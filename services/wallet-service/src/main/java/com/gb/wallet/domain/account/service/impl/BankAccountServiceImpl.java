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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankAccountServiceImpl implements BankAccountService {

    /** 계좌 인증 rate-limit 카운터 키 prefix(IP 단위). database.md §7 등록. */
    private static final String VERIFY_RATE_LIMIT_KEY_PREFIX = "ratelimit:account-verify:";
    /** 계좌 등록 직렬화 분산락 키 prefix(user 단위). database.md §7 등록. */
    private static final String REGISTER_LOCK_KEY_PREFIX = "lock:account-register:";

    private final BankAccountRepository bankAccountRepository;
    private final BankRepository bankRepository;
    private final BankClient bankClient;
    private final RateLimitHelper rateLimitHelper;
    private final VerifyRateLimitProperties verifyRateLimitProperties;
    private final DistributedLockHelper distributedLockHelper;

    /**
     * self-injection: {@link #registerAccount}가 분산락을 잡은 채 {@link #registerAccountLocked}를 AOP
     * 프록시를 통해 호출해야 {@code @Transactional}이 적용되고 락이 커밋 시점까지 유지된다(같은 빈 내부의
     * {@code this.registerAccountLocked()}는 프록시를 우회해 트랜잭션이 안 걸림). {@code @Lazy}로 빈 생성
     * 시점의 자기참조 순환을 끊는다. 필드 주입을 쓴 이유는 {@code ChargeServiceImpl}의 동일 패턴 javadoc 참고.
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
    public VerifyAccountResponse verifyAccount(VerifyAccountRequest request, String clientIp) {
        // IP 단위 고정 윈도 rate-limit — 한 출처의 외부 은행 인증 폭주를 막는다. 초과 시 ACCOUNT4005(429).
        // Redis 장애 시 fail-open(통과) — rate-limit은 보안 보조 장치이고 verify는 저위험(외부 호출만, DB 미사용).
        boolean allowed = rateLimitHelper.tryAcquire(
                VERIFY_RATE_LIMIT_KEY_PREFIX + clientIp,
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
        RLock lock = distributedLockHelper.tryLock(REGISTER_LOCK_KEY_PREFIX + userPublicId);
        if (lock == null) {
            // 락 획득 실패 → 503(fail-closed): "잠깐 거부"가 "조용히 중복 생성"보다 안전.
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return self.registerAccountLocked(userPublicId, request);
        } finally {
            // 락 보유자가 본인인 경우에만 해제(lease 만료로 다른 스레드가 가진 경우 안전).
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public AccountResponse registerAccountLocked(String userPublicId, RegisterAccountRequest request) {
        // TODO: DB UNIQUE 최종 안전망은 별도 마이그레이션 이슈로 연기(redis-refactor §5-③/§5-1). 현재는
        //       lock:account-register:{user} 분산락이 user 단위 직렬화로 중복 계좌·다중 주계좌를 막는다.
        //       후속: bank_accounts에 (user_public_id, bank_id, account_number) UNIQUE 제약 추가(중복 등록의
        //       최종 안전망) — MySQL은 부분 유니크 인덱스 미지원이라 is_primary 단일성은 제약만으론 못 막고
        //       락이 담당한다. 명세에 UNIQUE 정의가 없어(database.md §131 합의 + dev DB 중복 정리 + soft-delete
        //       재등록 정책이 얽혀 있음) Redis 작업과 분리해 마이그레이션 이슈에서 도입한다.
        if (bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                userPublicId, request.getBankCode(), request.getAccountNumber())) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);
        }

        Bank bank = bankRepository.findByCode(request.getBankCode())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));

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
                .mockAccountToken(request.getAccountToken())
                .isVirtual(false)
                .isPrimary(isPrimary)
                .isActive(true)
                .build();

        BankAccount saved = bankAccountRepository.save(account);
        return AccountResponse.from(saved);
    }
}
