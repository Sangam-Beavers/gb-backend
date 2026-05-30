package com.gb.wallet.domain.account.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 충전 실행 요청 본문. {@code POST /api/v1/accounts/{id}/charge}.
 *
 * <p>명세 §5: 금액은 요청도 string으로 받는다. 필드 타입을 {@link BigDecimal}로 두면 Jackson이
 * string→BigDecimal로 자동 매핑하므로 별도 처리 없이 받는다(금융 계산은 항상 BigDecimal). 통화는
 * 받지 않는다 — 충전은 KRW 고정이며 서비스 상수로 고정한다(§12, §5-4).
 *
 * <p>검증 실패는 모두 GlobalExceptionHandler가 COMMON4001(400)로 변환한다.
 */
@Getter
@NoArgsConstructor
public class ChargeRequest {

    @Schema(description = "충전 금액(string 십진수, KRW). 0 초과.", example = "1530000")
    @NotNull(message = "충전 금액은 필수입니다")
    @DecimalMin(value = "0", inclusive = false, message = "충전 금액은 0보다 커야 합니다")
    // DECIMAL(18,4): 정수부 최대 14자리 + 소수부 최대 4자리. 초과 정밀도는 형식 오류로 400 처리.
    @Digits(integer = 14, fraction = 4, message = "충전 금액 형식이 올바르지 않습니다")
    private BigDecimal amount;
}