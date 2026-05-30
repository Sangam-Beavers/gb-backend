package com.gb.wallet.domain.account.dto.response;

import com.gb.wallet.global.client.dto.AccountToken;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * {@code POST /api/v1/accounts/verify} 응답 data.
 *
 * <p>발급된 {@code account_token}은 클라이언트가 다음 단계({@code POST /accounts})에서 그대로 함께 전송해야 한다.
 * snake_case 변환은 전역 Jackson 설정으로 처리된다(필드 camelCase 유지).
 */
@Getter
public class VerifyAccountResponse {

    @Schema(description = "외부(Mock) 은행이 발급한 계좌 토큰. 계좌 등록 시 본문에 함께 전달한다.",
            example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
    private final String accountToken;

    private VerifyAccountResponse(String accountToken) {
        this.accountToken = accountToken;
    }

    public static VerifyAccountResponse from(AccountToken token) {
        return new VerifyAccountResponse(token.accountToken());
    }
}
