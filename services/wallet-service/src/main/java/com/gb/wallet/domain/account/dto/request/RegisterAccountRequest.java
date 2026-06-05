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
 * 단 서버는 이 값을 <b>신뢰·저장하지 않는다</b> — 등록 시 은행 verify를 재호출해 직접 발급받은 토큰을
 * 저장한다(10D wallet-account-charge-3, 토큰-계좌 바인딩 보장). 필드는 명세 §11 Body 호환을 위해
 * 받기만 한다({@code holderName}과 동일한 vestigial 패턴 — WACC-05).
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

    /**
     * 예금주명. {@code GET /accounts/holder}(예금주 실명 조회) 응답의 {@code account_holder_name}을
     * 그대로 전달한다 — 사용자 직접 입력이 아닌 외부 은행 검증 통과 값. 송금 확인증
     * ({@code Transaction.receiverName} snapshot)의 출처가 된다.
     *
     * <p>{@code POST /accounts/verify} 응답이 아님에 주의 — verify는 {@code account_token}만 반환한다
     * (api-spec.md §13 은행 연동·{@code VerifyAccountResponse} 정본).
     */
    @Schema(description = "예금주명. GET /accounts/holder 응답의 account_holder_name을 그대로 전달",
            example = "NGUYEN VAN A", maxLength = 100)
    @NotBlank
    @Size(max = 100)
    private String holderName;
}
