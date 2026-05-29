package com.gb.wallet.domain.transaction.dto.response;

import com.gb.wallet.global.common.enums.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Getter;

/**
 * POST /api/v1/transfers/fee 응답 data.
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환된다 — 필드는 camelCase로 두고
 * {@code @JsonProperty}를 붙이지 않는다.
 *
 * <p>금액은 명세에 따라 소수점 4자리 string으로 직렬화한다(잔액 조회와 동일 규칙).
 * setScale은 정책상 {@link RoundingMode#HALF_UP}로 고정 — DTO가 들어오는 BigDecimal을 한 번 더 안전 정규화.
 */
@Getter
public class TransferFeeResponse {

    @Schema(description = "송금 수수료 (string 십진수, 소수점 4자리)", example = "50.0000")
    private final String fee;

    @Schema(description = "수수료 통화 코드 (송금 통화와 동일)", example = "KRW",
            allowableValues = {"KRW", "USD", "PHP", "VND"})
    private final String feeCurrencyCode;

    @Schema(description = "총 출금 금액(amount + fee, string 십진수, 소수점 4자리)", example = "10050.0000")
    private final String totalDeductAmount;

    private TransferFeeResponse(String fee, String feeCurrencyCode, String totalDeductAmount) {
        this.fee = fee;
        this.feeCurrencyCode = feeCurrencyCode;
        this.totalDeductAmount = totalDeductAmount;
    }

    public static TransferFeeResponse of(BigDecimal fee, CurrencyType currency, BigDecimal totalDeduct) {
        return new TransferFeeResponse(
                fee.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                currency.name(),
                totalDeduct.setScale(4, RoundingMode.HALF_UP).toPlainString());
    }
}
