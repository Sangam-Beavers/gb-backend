package com.gb.wallet.domain.transaction.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 송금 PIN 설정/검증 공통 요청. 6자리 숫자.
 *
 * <p>형식(6자리 숫자) 위반은 @Pattern으로 1차 검증(COMMON4001). "PIN 불일치"(TRANSFER4007)는
 * 형식 문제가 아니라 값 대조 실패라 Service에서 처리한다 — 둘은 의미가 다르다.
 */
@Getter
@NoArgsConstructor
public class TransferPinRequest {

    @Schema(description = "송금 PIN (숫자 6자리)", example = "123456")
    @NotBlank(message = "PIN은 필수입니다")
    @Pattern(regexp = "\\d{6}", message = "PIN은 숫자 6자리여야 합니다")
    private String pin;
}
