package com.gb.wallet.global.client;

import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.client.dto.VerifyInitResult;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import java.math.BigDecimal;

/**
 * 외부 Mock 은행 서버를 호출하는 클라이언트 계약.
 * 본체와 Mock 은행은 장부(DB)가 완전히 분리돼 있고 HTTP로만 통신한다.
 *
 * <p>구현체는 Profile로 분리 — {@link MockBankClient}(@Profile dev,stage) /
 * RealBankClient(@Profile prod, 후속 PR). 비즈니스 로직은 이 인터페이스에만 의존하므로
 * 구현체를 교체해도 Service 코드는 바뀌지 않는다.
 *
 * <p>메서드와 Mock 엔드포인트 매핑 (API 명세 §13-1):
 * <ul>
 *   <li>{@link #inquiry}        → {@code POST /api/v1/bank/accounts/inquiry}</li>
 *   <li>{@link #initVerify}     → {@code POST /api/v1/bank/accounts/verify}   — 1원 입금 + 코드 생성</li>
 *   <li>{@link #confirmVerify}  → {@code POST /api/v1/bank/accounts/confirm}  — 코드 검증 → token 발급</li>
 *   <li>{@link #withdraw}       → {@code POST /api/v1/bank/transfers/withdrawal}</li>
 *   <li>{@link #payout}         → {@code POST /api/v1/bank/transfers/payout}</li>
 * </ul>
 */
public interface BankClient {

    /** 예금주 실명 조회. 계좌 없음 → {@code BANK4040}. */
    AccountHolder inquiry(String bankCode, String accountNumber);

    /**
     * 계좌 인증 요청(1원 소액이체 방식).
     * 해당 계좌에 1원을 입금하고 적요에 4자리 인증번호를 기재한다.
     * account_token은 발급하지 않으며, {@link #confirmVerify}에서 코드 검증 후 발급한다.
     *
     * <p>예금주 불일치 → {@code BANK4003}. 계좌 없음 → {@code BANK4040}.
     */
    VerifyInitResult initVerify(String bankCode, String accountNumber, String holderName);

    /**
     * 인증번호 확인 → account_token 발급.
     * 사용자가 입금 적요에서 읽은 4자리 코드를 검증한다.
     *
     * <p>코드 불일치 → {@code BANK4005}. 세션 없음/만료 → {@code BANK4006}.
     * 이미 사용된 코드 → {@code BANK4007}.
     */
    AccountToken confirmVerify(String bankCode, String accountNumber, String code);

    /**
     * 충전 출금(외부 계좌 차감). 동일 {@code idempotencyKey} 재요청 시 첫 응답을 재반환.
     * 잔액 부족 → {@code BANK4002}. 토큰 무효 → {@code BANK4010}.
     */
    WithdrawalResult withdraw(String accountToken, BigDecimal amount,
                              String currencyCode, String idempotencyKey);

    /**
     * 현금화 지급(외부 계좌 증액). 동일 {@code idempotencyKey} 재요청 시 첫 응답을 재반환.
     */
    PayoutResult payout(String bankCode, String accountNumber, BigDecimal amount,
                        String currencyCode, String idempotencyKey);
}
