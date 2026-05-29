package com.gb.wallet.domain.account.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/accounts/verify} 요청 본문.
 *
 * <p>snake_case ↔ camelCase 매핑은 전역 Jackson 설정({@code spring.jackson.property-naming-strategy:
 * SNAKE_CASE})으로 자동 처리되므로 {@code @JsonProperty}를 붙이지 않는다(CLAUDE.md §5).
 *
 * <p>길이 제약은 {@code bank_accounts} 스키마와 Mock 은행 명세를 따른다 — bank_code/account_number는
 * 등록 시점과 동일한 상한을 사용하고, holder_name은 한글 풀네임 + 외국인 영문 풀네임을 모두 수용할 수
 * 있도록 100자로 둔다(스키마에 별도 컬럼 없음, 사용자 입력 방어용).
 */
@Getter
@NoArgsConstructor
public class VerifyAccountRequest {

    @Schema(description = "은행 코드", example = "004", maxLength = 20)
    @NotBlank
    @Size(max = 20)
    private String bankCode;

    @Schema(description = "계좌번호(하이픈 없는 숫자 문자열)", example = "1234567890", maxLength = 100)
    @NotBlank
    @Size(max = 100)
    private String accountNumber;

    @Schema(description = "예금주명", example = "홍길동", maxLength = 100)
    @NotBlank
    @Size(max = 100)
    private String holderName;
}
