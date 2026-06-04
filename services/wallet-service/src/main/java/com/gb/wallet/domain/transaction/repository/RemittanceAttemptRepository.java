package com.gb.wallet.domain.transaction.repository;

import com.gb.wallet.domain.transaction.entity.RemittanceAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link RemittanceAttempt} CRUD.
 *
 * <p>{@link #existsByIdempotencyKey(String)}는 {@link com.gb.wallet.domain.transaction.service.impl.RemittanceAttemptWriterImpl#record}가
 * INSERT 전에 사전 체크해 {@code uk_remittance_attempts_idempotency_key} 위반 자체를 줄이는 용도다
 * ({@link com.gb.wallet.domain.wallet.repository.WalletBalanceRepository#existsByWalletAndCurrencyCode} 동일 패턴).
 * 잔여 race(체크 직후 다른 트랜잭션이 먼저 INSERT)는 Writer가 {@code DataIntegrityViolationException}으로 흡수한다.
 *
 * <p>향후 운영 reconcile 배치가 도입되면 그 시점에 필요한 메서드만 추가한다 — 예시(미작성):
 * {@code findByIdempotencyKey(String)} / {@code findAllByUserPublicIdAndAttemptedAtAfter(...)} 등.
 */
public interface RemittanceAttemptRepository extends JpaRepository<RemittanceAttempt, Long> {

    /** {@code idempotency_key}로 시도 흔적이 이미 존재하는지 확인한다. */
    boolean existsByIdempotencyKey(String idempotencyKey);
}
