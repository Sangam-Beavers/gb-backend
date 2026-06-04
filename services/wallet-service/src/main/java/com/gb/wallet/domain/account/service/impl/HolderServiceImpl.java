package com.gb.wallet.domain.account.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.account.service.HolderService;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.config.VerifyRateLimitProperties;
import com.gb.wallet.global.redis.RateLimitHelper;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link HolderService} 구현.
 * DB 호출이 없는 외부 API 어댑터지만, 다른 서비스와 일관성을 위해 {@code readOnly} 트랜잭션 경계를 유지한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HolderServiceImpl implements HolderService {

    /** 예금주 조회 rate-limit 카운터 키 prefix(사용자 단위 — WACC-03). */
    private static final String HOLDER_RATE_LIMIT_KEY_PREFIX = "ratelimit:account-holder:";

    private final BankClient bankClient;
    private final RateLimitHelper rateLimitHelper;
    // verify와 동급 정책(윈도/임계값)을 재사용한다 — 둘 다 외부 은행 PII 조회/인증 폭주 방어라 같은 강도가 적절.
    private final VerifyRateLimitProperties rateLimitProperties;

    @Override
    public AccountHolderResponse getAccountHolder(String bankCode, String accountNumber, String userPublicId) {
        // 사용자 단위 고정 윈도 rate-limit — 예금주 실명(PII) 무차별 조회(enumeration)를 막는다(WACC-03).
        // 위조불가 userPublicId로 키잉. 초과 시 COMMON4291(429). Redis 장애 시 fail-open(통과).
        boolean allowed = rateLimitHelper.tryAcquire(
                HOLDER_RATE_LIMIT_KEY_PREFIX + userPublicId,
                rateLimitProperties.limit(),
                Duration.ofSeconds(rateLimitProperties.windowSeconds()));
        if (!allowed) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }

        AccountHolder holder = bankClient.inquiry(bankCode, accountNumber);
        return AccountHolderResponse.from(holder);
    }
}
