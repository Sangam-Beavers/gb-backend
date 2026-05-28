package com.gb.wallet.global.client;

import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import java.math.BigDecimal;

/**
 * 외부 Mock 은행(Beaver/Quokka Bank) 서버를 호출하는 클라이언트 계약.
 * 본체와 Mock 은행은 장부(DB)가 완전히 분리돼 있고 HTTP로만 통신한다.
 *
 * <p>구현체는 Profile로 분리한다 — {@link MockBankClient}(@Profile dev,stage) /
 * RealBankClient(@Profile prod, 후속 PR에서 도입). 비즈니스 로직은 이 인터페이스에만 의존하므로
 * 구현체를 교체해도 Service 코드는 바뀌지 않는다.
 *
 * <p>메서드와 Mock 엔드포인트 매핑은 API 명세 §13-1 참고:
 * <ul>
 *   <li>{@link #inquiry} → {@code POST /api/v1/bank/accounts/inquiry} — 본체 {@code GET /accounts/holder} 지원</li>
 *   <li>{@link #verify} → {@code POST /api/v1/bank/accounts/verify} — 본체 {@code POST /accounts/verify} 지원</li>
 *   <li>{@link #withdraw} → {@code POST /api/v1/bank/transfers/withdrawal} — 본체 {@code POST /accounts/{id}/charge} 지원</li>
 *   <li>{@link #payout} → {@code POST /api/v1/bank/transfers/payout} — 본체 현금화(REMITTANCE) 지원</li>
 * </ul>
 */
public interface BankClient {

    /** 예금주 실명 조회. 계좌 없음 → {@code BANK4040}, 정상 시 예금주명 반환. */
    AccountHolder inquiry(String bankCode, String accountNumber);

    /** 계좌 인증. 예금주 불일치 → {@code BANK4003}, 정상 시 {@code account_token} 반환. */
    AccountToken verify(String bankCode, String accountNumber, String holderName);

    /**
     * 충전 출금(외부 계좌 차감). 동일 {@code idempotencyKey} 재요청 시 Mock 은행이 첫 응답을 재반환한다.
     * 잔액 부족 → {@code BANK4002}, 토큰 무효 → {@code BANK4010}.
     */
    WithdrawalResult withdraw(String accountToken, BigDecimal amount,
                              String currencyCode, String idempotencyKey);

    /**
     * 현금화 지급(외부 계좌 증액). 환율은 본체가 환전 시점에 적용하고, Mock에는 최종 외화 금액만 넘긴다.
     * 동일 {@code idempotencyKey} 재요청 시 Mock 은행이 첫 응답을 재반환한다.
     */
    PayoutResult payout(String bankCode, String accountNumber, BigDecimal amount,
                        String currencyCode, String idempotencyKey);
}