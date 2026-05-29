package com.gb.wallet.domain.transaction.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/transfers/fee 요청 본문.
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase ↔ snake_case 변환된다 — 필드는 camelCase로 두고
 * {@code @JsonProperty}를 붙이지 않는다.
 *
 * <p>Bean Validation으로 형식만 검증한다(필수/패턴). 통화 코드의 도메인 검증(KRW/USD/PHP/VND 외 거부)은
 * Service가 {@code CurrencyType.fromCode} + {@code TransferErrorCode.UNSUPPORTED_CURRENCY}로 처리.
 * 여기에 통화 enum 패턴을 박지 않는다 — 그러면 COMMON4001로 떨어져 명세 의도(TRANSFER4002)와 어긋남.
 */
public record TransferFeeRequest(

        @Schema(description = "송금 방식", example = "REMITTANCE",
                allowableValues = {"INTERNAL_TRANSFER", "REMITTANCE"})
        @NotBlank
        @Pattern(regexp = "^(INTERNAL_TRANSFER|REMITTANCE)$",
                message = "transfer_type must be INTERNAL_TRANSFER or REMITTANCE")
        String transferType,

        @Schema(description = "송금 통화 코드", example = "KRW")
        @NotBlank
        String currencyCode,

        @Schema(description = "송금 금액 (string 십진수, 소수점 최대 4자리)", example = "10000.0000")
        @NotBlank
        @Pattern(regexp = "^\\d+(\\.\\d{1,4})?$",
                message = "amount must be a positive decimal with up to 4 fractional digits")
        String amount
) {
}
