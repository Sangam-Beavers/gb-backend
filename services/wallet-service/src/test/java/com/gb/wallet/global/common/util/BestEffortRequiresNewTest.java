package com.gb.wallet.global.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * {@link BestEffortRequiresNew} 단위 테스트(wallet-account-charge-2). REQUIRES_NEW best-effort writer 호출의
 * 동시 UNIQUE race(UnexpectedRollbackException)만 흡수하고, 그 외 예외는 전파하는지 검증한다. 5개 호출부
 * (charge record/ensure, transfer receiver ensure, remittance record, exchange ensure)가 모두 이 헬퍼를 쓴다.
 */
class BestEffortRequiresNewTest {

    @Test
    @DisplayName("정상 실행: writerCall을 그대로 수행한다")
    void run_정상실행() {
        AtomicBoolean ran = new AtomicBoolean(false);

        BestEffortRequiresNew.run(() -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    @DisplayName("charge-2: UnexpectedRollbackException(동시 UNIQUE race로 REQUIRES_NEW rollback-only)은 흡수하고 진행")
    void run_UnexpectedRollback_흡수() {
        assertThatCode(() -> BestEffortRequiresNew.run(() -> {
            throw new UnexpectedRollbackException("Transaction silently rolled back because it has been marked as rollback-only");
        })).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("그 외 예외는 전파한다 — 동시 race 외의 실패까지 삼키지 않는다")
    void run_기타예외_전파() {
        assertThatThrownBy(() -> BestEffortRequiresNew.run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }
}
