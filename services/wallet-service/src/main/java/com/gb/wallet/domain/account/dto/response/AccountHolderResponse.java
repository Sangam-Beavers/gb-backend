package com.gb.wallet.domain.account.dto.response;

import com.gb.wallet.global.client.dto.AccountHolder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * GET /api/v1/accounts/holder 응답 data.
 *
 * <p>JSON 필드명은 전역 Jackson 설정({@code spring.jackson.property-naming-strategy: SNAKE_CASE})으로
 * 변환된다 — 필드는 camelCase로 두고 {@code @JsonProperty}를 붙이지 않는다
 * (accountHolderName → account_holder_name).
 */
@Getter
public class AccountHolderResponse {

    @Schema(description = "Mock 은행에서 조회된 예금주 실명", example = "홍길동")
    private final String accountHolderName;

    private AccountHolderResponse(String accountHolderName) {
        this.accountHolderName = accountHolderName;
    }

    public static AccountHolderResponse from(AccountHolder holder) {
        return new AccountHolderResponse(holder.accountHolderName());
    }
}
