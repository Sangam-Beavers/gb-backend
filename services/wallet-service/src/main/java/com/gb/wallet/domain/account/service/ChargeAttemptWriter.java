package com.gb.wallet.domain.account.service;

import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;

/**
 * CHARGE(충전) 외부 호출 시도 흔적 쓰기 헬퍼(WACC-01).
 *
 * <p>{@link com.gb.wallet.domain.transaction.service.RemittanceAttemptWriter}와 동일하게 별도 트랜잭션
 * 경계(REQUIRES_NEW)로 노출한다. 충전 흐름은 메인 {@code @Transactional} 안에서 외부 Mock 은행
 * {@code withdrawal}(외부계좌 차감)을 호출하는데, 호출이 timeout으로 떠도 은행 측엔 처리됐을 수 있다
 * (timeout-but-success). 메인 트랜잭션이 rollback되면 본 Transaction/audit 흔적이 모두 사라져 운영
 * reconcile에서 단서가 없으므로, 외부 호출 <b>직전</b> 흔적만 별도 커밋해 살려둔다.
 *
 * <p>흔적은 송금({@code remittance_attempts})과 분리된 {@code charge_attempts}에 박는다 — 충전(차감)과
 * 송금(증액)은 외부 계좌 기준 돈 방향이 정반대라 reconcile 교정 방향도 정반대이므로 유형을 섞지 않는다.
 */
public interface ChargeAttemptWriter {

    /**
     * CHARGE 시도 1건을 별도 트랜잭션으로 기록한다. 같은 {@code idempotencyKey}로 이미 행이 있으면
     * UNIQUE 위반을 흡수하고 정상 리턴한다(멱등성 race 정상 경로).
     *
     * @param idempotencyKey UNIQUE — 같은 키 재시도 시에도 흔적은 1행으로 유지
     * @param userPublicId   충전 요청 회원 논리 참조(UUID)
     * @param bankAccountId  검증된 외부 계좌 내부 id(Transaction.bankAccountId 패턴)
     * @param amount         시도 출금(차감)액
     * @param currency       시도 통화
     */
    void record(String idempotencyKey, String userPublicId, Long bankAccountId,
                BigDecimal amount, CurrencyType currency);
}
