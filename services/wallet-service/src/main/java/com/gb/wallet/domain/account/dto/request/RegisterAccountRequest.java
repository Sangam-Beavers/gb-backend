package com.gb.wallet.domain.account.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/accounts} 요청 본문.
 *
 * <p>{@code accountToken}은 직전 {@code POST /accounts/verify} 응답으로 받은 값을 그대로 전달한다.
 * 주 계좌(is_primary) 지정은 본 등록 API에서 받지 않는다 — 사용자의 첫 활성 계좌면 서비스가 자동으로
 * true로 설정하고, 그 외 변경은 명세 §11의 {@code PATCH /api/v1/accounts/{id}/primary}로만 한다.
 * 이렇게 분리해야 등록 흐름에서 다중 주 계좌(같은 사용자에 활성 is_primary=true가 둘 이상)가
 * 발생하지 않는다.
 *
 * <p>길이 제약은 {@code bank_accounts} 스키마(docs/database.md §bank_accounts)를 따른다
 * — {@code bank_code} VARCHAR(20) / {@code account_number} VARCHAR(100) / {@code mock_account_token} VARCHAR(36).
 */
@Getter
@NoArgsConstructor
public class RegisterAccountRequest {

    @Schema(description = "은행 코드", example = "004", maxLength = 20)
    @NotBlank
    @Size(max = 20)
    private String bankCode;

    @Schema(description = "계좌번호(하이픈 없는 숫자 문자열)", example = "1234567890", maxLength = 100)
    @NotBlank
    @Size(max = 100)
    private String accountNumber;

    @Schema(description = "verify 단계에서 발급받은 외부(Mock) 은행 토큰",
            example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c", maxLength = 36)
    @NotBlank
    @Size(max = 36)
    private String accountToken;
}
