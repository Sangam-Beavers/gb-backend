package com.gb.wallet.global.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * REQUIRES_NEW "best-effort 보장" writer(흔적/잔액 행 INSERT) 호출을 감싸 {@link UnexpectedRollbackException}을
 * 흡수한다(wallet-account-charge-2).
 *
 * <p><b>왜 필요한가:</b> {@code ChargeAttemptWriter}·{@code RemittanceAttemptWriter}·{@code WalletBalanceWriter}는
 * {@code REQUIRES_NEW}로 분리돼 메인 트랜잭션 오염을 막지만, 동시에 같은 키로 INSERT가 겹치면 내부
 * {@code saveAndFlush}가 {@code DataIntegrityViolationException}을 던지고 — 메서드가 그것을 catch해도 —
 * Hibernate가 flush 실패 시점에 그 {@code REQUIRES_NEW} 트랜잭션을 rollback-only로 마킹한다. 그 결과
 * {@code REQUIRES_NEW} 커밋 시 {@link UnexpectedRollbackException}이 <b>호출자</b>로 전파된다(내부 catch로는
 * 못 막는다 — 커밋은 AOP 프록시가 메서드 반환 *후* 수행). 이 경우 흔적/행은 경쟁 INSERT로 <b>이미 존재</b>해
 * 의미상 성공이므로, 흡수하고 본업(충전/송금/환전)을 계속한다 — 정상 거래가 generic 500으로 깨지지 않게 한다.
 *
 * <p><b>주의:</b> 위 best-effort writer 호출 <b>전용</b>이다. 이들 writer는 내부에서
 * {@code DataIntegrityViolationException}만 흡수하고 다른 롤백 사유가 없으므로, 여기서 잡히는
 * {@code UnexpectedRollbackException}은 항상 "동시 UNIQUE race"를 의미한다. 다른 롤백 사유를 가릴 수 있으니
 * 일반 비즈니스 트랜잭션 호출에는 쓰지 말 것.
 */
@Slf4j
public final class BestEffortRequiresNew {

    private BestEffortRequiresNew() {
    }

    /**
     * {@code writerCall}을 실행하고, 동시 UNIQUE race로 인한 {@link UnexpectedRollbackException}만 흡수한다
     * (흔적/행은 경쟁 INSERT로 이미 존재 → best-effort 보장 충족).
     */
    public static void run(Runnable writerCall) {
        try {
            writerCall.run();
        } catch (UnexpectedRollbackException concurrentRowAlreadyExists) {
            log.debug("REQUIRES_NEW best-effort writer 동시 UNIQUE race 흡수 — 흔적/행은 이미 존재, 진행.");
        }
    }
}
