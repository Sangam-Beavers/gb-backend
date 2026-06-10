package com.gb.wallet.domain.account.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/accounts} 요청 본문.
 *
 * <p>{@code accountToken}은 1원 인증 플로우 도입 이전 호환성 유지를 위해 남겨진 vestigial 필드다.
 * 서버는 이 값을 사용하지 않는다 — 등록 시 {@code confirmAccount}가 Redis에 저장한 토큰을
 * {@link com.gb.wallet.global.redis.VerifySessionStore#consume}으로 소비한다(WACC-05/charge-3).
 * 따라서 클라이언트는 이 필드를 생략해도 된다(@NotBlank 제거).
 *
 * <p>주 계좌(is_primary) 지정은 본 등록 API에서 받지 않는다 — 사용자의 첫 활성 계좌면 서비스가 자동으로
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

    /**
     * 1원 인증 플로우에서는 사용하지 않는 vestigial 필드.
     * 서버는 {@link com.gb.wallet.global.redis.VerifySessionStore}에서 토큰을 소비하므로
     * 클라이언트가 이 필드를 보내지 않아도 된다.
     */
    @Schema(description = "[vestigial] 이전 플로우 호환 필드 — 서버가 무시함", maxLength = 36)
    @Size(max = 36)
    private String accountToken;

    /**
     * 예금주명. {@code GET /accounts/holder}(예금주 실명 조회) 응답의 {@code account_holder_name}을
     * 그대로 전달한다 — 사용자 직접 입력이 아닌 외부 은행 검증 통과 값. 송금 확인증
     * ({@code Transaction.receiverName} snapshot)의 출처가 된다.
     */
    @Schema(description = "예금주명. GET /accounts/holder 응답의 account_holder_name을 그대로 전달",
            example = "NGUYEN VAN A", maxLength = 100)
    @NotBlank
    @Size(max = 100)
    private String holderName;
}
