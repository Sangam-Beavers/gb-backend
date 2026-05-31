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
 *
 * <p><b>클래스 레벨 {@link Schema} example을 명시한 이유</b>:
 * springdoc은 Request 본문 예시를 생성할 때 전역 Jackson SNAKE_CASE 전략을 무시하고 Java 필드명을
 * 그대로 박는다. 그 결과 Swagger UI "Try it out" 기본값이 {@code transferType}처럼 camelCase로
 * 보여, 사용자가 그대로 보내면 Jackson 역직렬화가 모두 null로 떨어지고 {@code @NotBlank}가 터져
 * COMMON4001 "공백일 수 없습니다"가 응답된다. 클래스 레벨 example로 snake_case 본문을 박아 우회.
 */
@Schema(
        description = "송금 수수료 조회 요청",
        example = """
                {
                  "transfer_type": "REMITTANCE",
                  "currency_code": "KRW",
                  "amount": "10000.0000"
                }
                """)
public record TransferFeeRequest(

        @Schema(description = "송금 방식", example = "REMITTANCE",
                allowableValues = {"INTERNAL_TRANSFER", "REMITTANCE"})
        @NotBlank
        // enum 후보값 검증은 Bean Validation @Pattern으로 박지 않는다(CLAUDE.md §6 규칙).
        // Service에서 TransactionType.fromCode + filter로 검증해 TRANSFER4003으로 매핑.
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
