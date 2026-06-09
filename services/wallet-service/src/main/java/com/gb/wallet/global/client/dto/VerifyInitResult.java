package com.gb.wallet.global.client.dto;

/**
 * Mock 은행 {@code POST /api/v1/bank/accounts/verify} 응답(1원 소액이체 방식).
 *
 * <p>구 verify는 즉시 {@code account_token}을 반환했으나, 새 방식은 1원을 입금한 뒤
 * 인증번호 4자리를 적요에 기재한다. 사용자가 코드를 확인해
 * {@code POST /accounts/confirm}으로 제출하면 {@link AccountToken}이 발급된다.
 */
public record VerifyInitResult(boolean pending, String expiresAt) {}
