package com.gb.wallet.domain.transaction.dto.response;

import com.gb.wallet.global.client.dto.AccountHolder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * GET /api/v1/transfers/account-holder 응답 data.
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환된다
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE}). 본체 응답 DTO는 어댑터 경계
 * 안쪽이라 {@code @JsonProperty}를 붙이지 않는다 — MockBankClient의 wire-format record가
 * @JsonProperty로 명시 매핑하는 것과는 의도가 다르다.
 */
@Getter
public class AccountHolderResponse {

    @Schema(description = "외부 은행에서 조회된 예금주명", example = "김민수")
    private final String accountHolderName;

    private AccountHolderResponse(String accountHolderName) {
        this.accountHolderName = accountHolderName;
    }

    public static AccountHolderResponse from(AccountHolder holder) {
        return new AccountHolderResponse(holder.accountHolderName());
    }
}
