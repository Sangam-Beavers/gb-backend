package com.gb.wallet.domain.account.repository;

import com.gb.wallet.domain.account.entity.ChargeAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link ChargeAttempt} CRUD.
 *
 * <p>{@link #existsByIdempotencyKey(String)}는 {@link com.gb.wallet.domain.account.service.impl.ChargeAttemptWriterImpl#record}가
 * INSERT 전에 사전 체크해 {@code uk_charge_attempts_idempotency_key} 위반 자체를 줄이는 용도다
 * (RemittanceAttemptRepository 동일 패턴). 잔여 race는 Writer가 {@code DataIntegrityViolationException}으로 흡수한다.
 */
public interface ChargeAttemptRepository extends JpaRepository<ChargeAttempt, Long> {

    /** {@code idempotency_key}로 시도 흔적이 이미 존재하는지 확인한다. */
    boolean existsByIdempotencyKey(String idempotencyKey);
}