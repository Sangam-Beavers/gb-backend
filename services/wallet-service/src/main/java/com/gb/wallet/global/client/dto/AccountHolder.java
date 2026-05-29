package com.gb.wallet.global.client.dto;

/**
 * Mock 은행 {@code POST /api/v1/bank/accounts/inquiry} 응답에서 발췌한 예금주 정보.
 *
 * <p>본체 API {@code GET /api/v1/accounts/holder}로 노출되는 {@code account_holder_name}의 원천이다.
 */
public record AccountHolder(String accountHolderName) {
}