package com.gb.wallet.domain.transaction.repository;

import com.gb.wallet.domain.transaction.entity.RemittanceAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link RemittanceAttempt} CRUD. 현 사이클은 Writer가 {@code save}만 호출하므로 추가 메서드 없음(YAGNI).
 *
 * <p>향후 운영 reconcile 배치가 도입되면 그 시점에 필요한 메서드만 추가한다 — 예시(미작성):
 * {@code findByIdempotencyKey(String)} / {@code findAllByUserPublicIdAndAttemptedAtAfter(...)} 등.
 */
public interface RemittanceAttemptRepository extends JpaRepository<RemittanceAttempt, Long> {
}
