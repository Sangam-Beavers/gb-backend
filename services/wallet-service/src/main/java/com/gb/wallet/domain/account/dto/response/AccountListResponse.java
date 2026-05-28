package com.gb.wallet.domain.account.dto.response;

import com.gb.wallet.domain.account.entity.BankAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * GET /api/v1/accounts 응답 data.
 *
 * <p>JSON 필드명은 <b>전역</b> Jackson 설정으로 변환된다
 * (application.yaml: {@code spring.jackson.property-naming-strategy: SNAKE_CASE}).
 * 따라서 필드는 camelCase로 두고 {@code @JsonProperty}를 붙이지 않는다
 * (accountPublicId → account_public_id 자동 변환).
 *
 * <p>빈 결과는 404가 아닌 200 + {@code accounts: []}로 응답한다.
 */
@Getter
public class AccountListResponse {

    @Schema(description = "등록된 활성 계좌 목록 (주 계좌 우선, 최신 등록순)")
    private final List<AccountResponse> accounts;

    private AccountListResponse(List<AccountResponse> accounts) {
        this.accounts = accounts;
    }

    public static AccountListResponse from(List<BankAccount> bankAccounts) {
        return new AccountListResponse(
                bankAccounts.stream().map(AccountResponse::from).toList());
    }
}
