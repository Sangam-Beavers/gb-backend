package com.gb.wallet.domain.exchange.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 환전 견적 요청. 금액은 string으로 받아 Service에서 BigDecimal로 변환한다.
 *
 * <p>{@code exchangeType}/{@code from}/{@code to} 통화 코드는 @NotBlank로만 1차 검증하고,
 * 실제 enum/지원 통화 여부는 Service에서 변환 시도 후 실패 시 도메인 에러(TRANSFER4002)로 처리한다
 * (CLAUDE.md §6 — @Pattern으로 enum 후보를 박지 않는다).
 */
@Getter
@NoArgsConstructor
public class QuoteRequest {

    @Schema(description = "환전 유형 (EXCHANGE: 원화→외화 / RE_EXCHANGE: 외화→원화)", example = "EXCHANGE")
    @NotBlank(message = "환전 유형은 필수입니다")
    private String exchangeType;

    @Schema(description = "출금 통화 코드 (ISO 4217)", example = "KRW")
    @NotBlank(message = "출금 통화는 필수입니다")
    private String fromCurrencyCode;

    @Schema(description = "입금 통화 코드 (ISO 4217)", example = "USD")
    @NotBlank(message = "입금 통화는 필수입니다")
    private String toCurrencyCode;

    @Schema(description = "환전 신청 금액 (string 십진수, 소수점 최대 4자리, 양수)", example = "100000.0000")
    @NotBlank(message = "금액은 필수입니다")
    // 양수 십진수만 통과: 0, 0.0, 0.0000 차단(부정형 lookahead). 송금(TransferExecuteRequest)과 동일 패턴.
    @Pattern(regexp = "^(?!0+(\\.0{1,4})?$)\\d+(\\.\\d{1,4})?$",
            message = "amount must be a positive decimal with up to 4 fractional digits")
    private String amount;
}
