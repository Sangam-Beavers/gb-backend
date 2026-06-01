package com.gb.wallet.domain.transaction.service.impl;

import com.gb.wallet.domain.transaction.entity.RemittanceAttempt;
import com.gb.wallet.domain.transaction.repository.RemittanceAttemptRepository;
import com.gb.wallet.domain.transaction.service.RemittanceAttemptWriter;
import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RemittanceAttemptWriter} 구현. {@link com.gb.wallet.domain.wallet.service.impl.WalletBalanceWriterImpl#ensureBalanceRow}
 * 패턴 그대로 차용 — REQUIRES_NEW로 호출 측 트랜잭션과 독립 커밋된다.
 *
 * <p>REMITTANCE 흐름에서 외부 호출 직전에 호출된다. 메인 트랜잭션이 이후 rollback되더라도 본 INSERT는
 * 이미 커밋돼 살아남으므로, timeout-but-success 후 reconcile 입력 자료가 보존된다.
 *
 * <p>동시에 같은 {@code idempotency_key}로 두 시도가 들어오면 한쪽은
 * {@code uk_remittance_attempts_idempotency_key} 위반을 받지만, 행은 이미 존재해 의미상 흔적이
 * 남아 있는 것이므로 흡수(no-op)한다. 메인 트랜잭션이 이 위반 때문에 rollback-only로 오염되지 않도록
 * 메인과 분리한 이유이기도 하다(WalletBalanceWriterImpl 동일 논리).
 */
@Service
@RequiredArgsConstructor
public class RemittanceAttemptWriterImpl implements RemittanceAttemptWriter {

    private final RemittanceAttemptRepository remittanceAttemptRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String idempotencyKey, String userPublicId, Long bankAccountId,
                       BigDecimal amount, CurrencyType currency) {
        try {
            remittanceAttemptRepository.saveAndFlush(RemittanceAttempt.builder()
                    .idempotencyKey(idempotencyKey)
                    .userPublicId(userPublicId)
                    .bankAccountId(bankAccountId)
                    .amount(amount)
                    .currencyCode(currency)
                    .attemptedAt(LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException concurrentAttempt) {
            // 같은 idempotency_key로 다른 트랜잭션이 먼저 흔적을 남겼다 → 흔적은 존재하므로 무시.
            // 멱등성 재요청/race에서 정상 경로다. 본 호출자(메인 트랜잭션)는 영향 없이 외부 호출로 진행한다.
        }
    }
}
