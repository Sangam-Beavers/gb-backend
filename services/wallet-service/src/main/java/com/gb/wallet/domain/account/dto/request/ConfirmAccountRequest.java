package com.gb.wallet.domain.account.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/accounts/confirm} 요청 본문.
 *
 * <p>직전 {@code POST /accounts/verify}(1원 소액이체) 응답으로 1원이 입금된 계좌의
 * 입금 적요에서 읽은 인증번호 4자리를 제출한다. 검증 성공 시 account_token이 발급되고
 * 서버 세션(Redis)에 저장된다. 이후 {@code POST /accounts}(계좌 등록) 시 소비된다.
 */
@Getter
@NoArgsConstructor
public class ConfirmAccountRequest {

    @Schema(description = "은행 코드", example = "004", maxLength = 20)
    @NotBlank
    @Size(max = 20)
    private String bankCode;

    @Schema(description = "계좌번호", example = "1234567890", maxLength = 100)
    @NotBlank
    @Size(max = 100)
    private String accountNumber;

    @Schema(description = "입금 적요에 표시된 인증번호 4자리", example = "2814", minLength = 4, maxLength = 4)
    @NotBlank
    @Pattern(regexp = "\\d{4}", message = "인증번호는 숫자 4자리여야 합니다.")
    private String code;
}
