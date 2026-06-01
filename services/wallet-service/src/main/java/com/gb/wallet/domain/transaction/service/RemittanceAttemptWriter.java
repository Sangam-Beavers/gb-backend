package com.gb.wallet.domain.transaction.service;

import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;

/**
 * REMITTANCE 외부 호출 시도 흔적 쓰기 헬퍼.
 *
 * <p>{@link com.gb.wallet.domain.wallet.service.WalletBalanceWriter}와 동일하게 별도 트랜잭션 경계
 * (REQUIRES_NEW)로 노출한다. REMITTANCE 흐름은 메인 {@code @Transactional} 안에서 외부 Mock 은행
 * payout을 호출하는데, 호출이 timeout으로 떠도 은행 측엔 처리됐을 수 있다(timeout-but-success).
 * 메인 트랜잭션이 rollback되면 본 Transaction/audit 흔적이 모두 사라져 운영 reconcile에서 단서가
 * 없으므로, 외부 호출 *직전* 흔적만 별도 커밋해 살려둔다.
 *
 * <p>흔적은 {@code transaction_audit_logs}가 아닌 신규 {@code remittance_attempts}에 박는다 —
 * audit log는 {@code transaction_id} NOT NULL이라 본 Transaction INSERT 전엔 행을 만들 수 없고,
 * 또 "거래에 1:1로 묶이는 흔적" 의미를 흐리지 않기 위해서다.
 */
public interface RemittanceAttemptWriter {

    /**
     * REMITTANCE 시도 1건을 별도 트랜잭션으로 기록한다. 같은 {@code idempotencyKey}로 이미 행이 있으면
     * UNIQUE 위반을 흡수하고 정상 리턴한다(멱등성 race 정상 경로).
     *
     * @param idempotencyKey UNIQUE — 같은 키 재시도 시에도 흔적은 1행으로 유지
     * @param userPublicId   송신자 회원 논리 참조(UUID)
     * @param bankAccountId  검증된 외부 계좌 내부 id(Transaction.bankAccountId 패턴)
     * @param amount         시도 차감액(amount + fee)
     * @param currency       시도 통화
     */
    void record(String idempotencyKey, String userPublicId, Long bankAccountId,
                BigDecimal amount, CurrencyType currency);
}
