package com.gb.wallet.domain.account.service.impl;

import com.gb.wallet.domain.account.entity.ChargeAttempt;
import com.gb.wallet.domain.account.repository.ChargeAttemptRepository;
import com.gb.wallet.domain.account.service.ChargeAttemptWriter;
import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ChargeAttemptWriter} 구현. {@link com.gb.wallet.domain.transaction.service.impl.RemittanceAttemptWriterImpl}
 * 패턴 그대로 차용 — REQUIRES_NEW로 호출 측(충전 메인) 트랜잭션과 독립 커밋된다.
 *
 * <p>충전 흐름에서 외부 {@code withdrawal} 직전에 호출된다. 메인 트랜잭션이 이후 rollback되더라도 본 INSERT는
 * 이미 커밋돼 살아남으므로, timeout-but-success(또는 외부 성공 후 비재시도성 로컬 실패) 후 reconcile 입력
 * 자료가 보존된다.
 *
 * <p>동시에 같은 {@code idempotency_key}로 두 시도가 들어오면 한쪽은 {@code uk_charge_attempts_idempotency_key}
 * 위반을 받지만, 행은 이미 존재해 의미상 흔적이 남아 있는 것이므로 흡수(no-op)한다. REQUIRES_NEW로 분리해
 * 메인 트랜잭션이 이 위반으로 rollback-only 오염되지 않게 한다(RemittanceAttemptWriterImpl 동일 논리).
 */
@Service
@RequiredArgsConstructor
public class ChargeAttemptWriterImpl implements ChargeAttemptWriter {

    private final ChargeAttemptRepository chargeAttemptRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String idempotencyKey, String userPublicId, Long bankAccountId,
                       BigDecimal amount, CurrencyType currency) {
        // (1) 사전 체크 — 흔적이 이미 있으면 INSERT 자체를 건너뛴다(멱등 재요청·재시도에서 대부분 여기서 회피).
        if (chargeAttemptRepository.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        // (2) 잔여 race 흡수 — 사전 체크 직후 다른 트랜잭션이 같은 키로 먼저 INSERT한 경우에만 도달한다.
        //     REQUIRES_NEW로 분리돼 있어 본 트랜잭션만 rollback되고 메인은 정상 진행한다.
        try {
            chargeAttemptRepository.saveAndFlush(ChargeAttempt.builder()
                    .idempotencyKey(idempotencyKey)
                    .userPublicId(userPublicId)
                    .bankAccountId(bankAccountId)
                    .amount(amount)
                    .currencyCode(currency)
                    .attemptedAt(LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException concurrentAttempt) {
            // 같은 idempotency_key로 다른 트랜잭션이 먼저 흔적을 남겼다 → 흔적은 존재하므로 무시.
        }
    }
}