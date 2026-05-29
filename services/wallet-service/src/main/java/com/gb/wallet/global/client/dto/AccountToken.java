package com.gb.wallet.global.client.dto;

/**
 * Mock 은행 {@code POST /api/v1/bank/accounts/verify} 응답에서 발췌한 인증 토큰.
 *
 * <p>{@code bank_accounts.mock_account_token}에 저장돼 충전 시 {@code withdrawal} 호출에 사용된다.
 */
public record AccountToken(String accountToken) {
}